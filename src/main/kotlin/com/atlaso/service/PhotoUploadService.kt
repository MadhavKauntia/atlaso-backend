package com.atlaso.service

import com.atlaso.controller.dto.ConfirmUploadRequest
import com.atlaso.controller.dto.InitiateUploadRequest
import com.atlaso.controller.dto.InitiateUploadResponse
import com.atlaso.domain.photo.GeoLocation
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.UploadGrant
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UploadGrantRepository
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.file.FileTypeDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class PhotoNotFoundException(id: UUID) : RuntimeException("Photo not found: $id")

@Service
@Transactional
class PhotoUploadService(
    private val photoRepository: PhotoRepository,
    private val storageService: StorageService,
    private val tripService: TripService,
    private val uploadGrantRepository: UploadGrantRepository,
    private val tripRepository: TripRepository
) {
    private val logger = LoggerFactory.getLogger(PhotoUploadService::class.java)

    companion object {
        val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
        )

        private val HEIC_CONTENT_TYPES = setOf("image/heic", "image/heif")

        /** Caps that protect storage and (mainly) per-book vision-analysis cost. Deliberately
         *  generous — trips may hold many large DSLR-quality images. */
        const val MAX_PHOTOS_PER_TRIP = 1000
        const val MAX_UPLOAD_BATCH = 100 // photos per single initiate/confirm call
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB per object (fits high-MP DSLR JPEG/HEIC)
        // Cumulative per-trip byte budget (confirmed + reserved). Set at the count×per-photo
        // ceiling so it never blocks a legitimate full trip, while the atomic reservation still
        // prevents unbounded growth from unconfirmed grants.
        const val MAX_BYTES_PER_TRIP = 50L * 1024 * 1024 * 1024 // 50 GB
        const val MAX_IMAGE_DIMENSION = 30000 // per-side pixel cap
        const val MAX_IMAGE_PIXELS = 200_000_000L // 200 MP — headroom for high-res DSLR/medium-format
        // Outstanding (unconsumed) grants count toward the trip quota until they expire, so a
        // caller can't mint unlimited reservations by never confirming.
        val GRANT_TTL: Duration = Duration.ofHours(24)
    }

    fun uploadPhoto(tripId: UUID, file: MultipartFile, userId: UUID): Photo {
        val trip = tripService.getTrip(tripId, userId)

        val contentType = file.contentType
            ?: throw IllegalArgumentException("File content type is required")
        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw IllegalArgumentException("Unsupported file type: $contentType. Allowed: $ALLOWED_CONTENT_TYPES")
        }

        val photoId = UUID.randomUUID()
        val isHeic = contentType in HEIC_CONTENT_TYPES

        // Convert HEIC to JPEG for browser/PDF compatibility; other formats stored as-is (EXIF preserved)
        val (storageBytes, storedContentType, extension) = if (isHeic) {
            val converted = convertToJpeg(file)
            Triple(converted, "image/jpeg", "jpg")
        } else {
            val ext = file.originalFilename?.substringAfterLast('.', "jpg") ?: "jpg"
            Triple(file.bytes, contentType, ext)
        }

        val storageKey = "$tripId/$photoId.$extension"
        storageService.store(storageKey, ByteArrayInputStream(storageBytes), storedContentType)

        val metadata = extractMetadata(storageBytes)

        val photo = Photo(
            trip = trip,
            storageKey = storageKey,
            originalFilename = file.originalFilename ?: "unknown",
            contentType = storedContentType,
            fileSize = storageBytes.size.toLong(),
            metadata = metadata
        )

        val saved = photoRepository.save(photo)
        logger.info("Uploaded photo: {} for trip: {}", saved.id, tripId)

        if (trip.status == TripStatus.CREATED) {
            tripService.updateStatus(tripId, TripStatus.UPLOADING_PHOTOS)
        }

        return saved
    }

    @Transactional(readOnly = true)
    fun getPhotosForTrip(tripId: UUID, userId: UUID? = null, guestToken: String? = null): List<Photo> {
        tripService.assertTripAccess(tripId, userId, guestToken)
        return photoRepository.findByTripId(tripId)
    }

    @Transactional(readOnly = true)
    fun getPhoto(photoId: UUID, tripId: UUID, userId: UUID): Photo {
        tripService.getTrip(tripId, userId)
        return photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
    }

    @Transactional(readOnly = true)
    fun getPhotoByTripAndId(photoId: UUID, tripId: UUID, userId: UUID? = null, guestToken: String? = null): Photo {
        tripService.assertTripAccess(tripId, userId, guestToken)
        return photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
    }

    fun rotatePhoto(photoId: UUID, degrees: Int, tripId: UUID, userId: UUID): Photo {
        require(degrees in listOf(0, 90, 180, 270)) { "Rotation must be 0, 90, 180, or 270" }
        tripService.getTrip(tripId, userId)
        val photo = photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
        photo.rotation = (photo.rotation + degrees) % 360
        val saved = photoRepository.save(photo)
        logger.info("Rotated photo {} to {}°", photoId, saved.rotation)
        return saved
    }

    fun initiateUploads(tripId: UUID, requests: List<InitiateUploadRequest>, userId: UUID? = null, guestToken: String? = null): List<InitiateUploadResponse> {
        tripService.assertTripAccess(tripId, userId, guestToken) // owner JWT (claimed) or guest token
        require(requests.isNotEmpty() && requests.size <= MAX_UPLOAD_BATCH) {
            "Between 1 and $MAX_UPLOAD_BATCH photos may be initiated per request."
        }
        // Validate every request up front (type + sizes) before reserving anything.
        requests.forEach { req ->
            require(req.contentType in ALLOWED_CONTENT_TYPES) { "Unsupported file type: ${req.contentType}" }
            require(req.fileSize in 1..MAX_FILE_SIZE_BYTES) {
                "Each photo must be between 1 byte and ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB."
            }
            req.thumbnailFileSize?.let { require(it in 1..MAX_FILE_SIZE_BYTES) { "Invalid thumbnail size" } }
        }

        // Atomic reservation: row-lock the trip so concurrent initiate calls can't each observe
        // the same free capacity and overshoot the count/byte quota.
        tripRepository.findByIdForUpdate(tripId).orElseThrow { TripNotFoundException(tripId) }
        val cutoff = Instant.now().minus(GRANT_TTL)

        // Quota counts confirmed photos AND outstanding (unexpired, unconsumed) reservations.
        val usedCount = photoRepository.countByTripId(tripId) +
            uploadGrantRepository.countByTripIdAndConsumedFalseAndCreatedAtAfter(tripId, cutoff)
        if (usedCount + requests.size > MAX_PHOTOS_PER_TRIP) {
            throw IllegalArgumentException("A book can hold at most $MAX_PHOTOS_PER_TRIP photos.")
        }
        // Cumulative byte quota: confirmed bytes + reserved bytes + this batch.
        val usedBytes = photoRepository.sumFileSizeByTripId(tripId) +
            uploadGrantRepository.sumReservedBytes(tripId, cutoff)
        val batchBytes = requests.sumOf { it.fileSize + (it.thumbnailFileSize ?: 0L) }
        if (usedBytes + batchBytes > MAX_BYTES_PER_TRIP) {
            throw IllegalArgumentException("Uploads for this trip exceed the ${MAX_BYTES_PER_TRIP / (1024 * 1024 * 1024)} GB limit.")
        }

        return requests.map { req ->
            val ext = when (req.contentType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                else -> req.filename.substringAfterLast('.', "jpg")
            }
            val photoId = UUID.randomUUID()
            val storageKey = "$tripId/$photoId.$ext"
            // Bind Content-Length into the presigned PUT so a stolen URL can't be
            // used to upload an object larger than the declared (capped) size.
            val uploadUrl = storageService.getUploadUrl(storageKey, req.contentType, req.fileSize)
            // If the client generated a thumbnail, also hand back a presigned PUT
            // for it (always JPEG, Content-Length bound to the declared size).
            val (thumbKey, thumbUploadUrl) = req.thumbnailFileSize?.let { size ->
                val key = "$tripId/${photoId}_thumb.jpg"
                key to storageService.getUploadUrl(key, "image/jpeg", size)
            } ?: (null to null)
            // Record the grant so confirm can only register a key we handed out, exactly once.
            uploadGrantRepository.save(
                UploadGrant(
                    tripId = tripId,
                    photoId = photoId,
                    storageKey = storageKey,
                    thumbnailKey = thumbKey,
                    contentType = req.contentType,
                    maxSizeBytes = req.fileSize,
                    thumbnailMaxSizeBytes = req.thumbnailFileSize,
                )
            )
            InitiateUploadResponse(
                photoId = photoId,
                storageKey = storageKey,
                uploadUrl = uploadUrl,
                thumbnailStorageKey = thumbKey,
                thumbnailUploadUrl = thumbUploadUrl
            )
        }
    }

    fun confirmUploads(tripId: UUID, confirmations: List<ConfirmUploadRequest>, userId: UUID? = null, guestToken: String? = null): List<Photo> {
        tripService.assertTripAccess(tripId, userId, guestToken) // owner JWT (claimed) or guest token
        require(confirmations.isNotEmpty() && confirmations.size <= MAX_UPLOAD_BATCH) {
            "Between 1 and $MAX_UPLOAD_BATCH photos may be confirmed per request."
        }
        val trip = tripService.getTrip(tripId)
        val cutoff = Instant.now().minus(GRANT_TTL)
        val photos = confirmations.map { conf ->
            // Bind to the server-recorded initiation: only a key we handed out for THIS photo
            // on THIS trip can be confirmed, once, before it expires.
            val grant = uploadGrantRepository.findByPhotoIdAndTripId(conf.photoId, tripId)
                ?: throw IllegalArgumentException("No upload was initiated for this photo")
            require(grant.createdAt.isAfter(cutoff)) { "This upload has expired — please re-upload" }
            require(grant.storageKey == conf.storageKey) { "Storage key does not match the initiated upload" }
            require(grant.thumbnailKey == conf.thumbnailStorageKey) { "Thumbnail key does not match the initiated upload" }

            // HEAD main object: exists, exact reserved size, and (soft) content-type match.
            val head = storageService.head(grant.storageKey) ?: throw IllegalArgumentException("Uploaded object not found")
            require(head.contentLength == grant.maxSizeBytes) { "Uploaded object size does not match the initiated upload" }
            head.contentType?.substringBefore(';')?.trim()?.let { actual ->
                require(actual.equals(grant.contentType, ignoreCase = true)) { "Uploaded object content type mismatch" }
            }
            // HEAD thumbnail: exists and matches the reserved thumbnail size.
            grant.thumbnailKey?.let { tk ->
                val th = storageService.head(tk) ?: throw IllegalArgumentException("Thumbnail object not found")
                grant.thumbnailMaxSizeBytes?.let { require(th.contentLength == it) { "Thumbnail size does not match the initiated upload" } }
            }

            // Validate the actual bytes are a real image of an allowed format with safe dimensions.
            val dims = validateImageObject(grant.storageKey)

            // Atomically consume the grant — only one concurrent confirm can win, enforcing one-time use.
            require(uploadGrantRepository.markConsumed(grant.id!!) == 1) { "This upload was already confirmed" }

            val takenAt = conf.takenAt?.let { Instant.ofEpochMilli(it) }
            Photo(
                trip = trip,
                storageKey = grant.storageKey,          // server-recorded key, not the client's
                thumbnailKey = grant.thumbnailKey,
                originalFilename = conf.originalFilename,
                contentType = grant.contentType,        // server-recorded content type
                fileSize = grant.maxSizeBytes,          // presigned PUT bound Content-Length to this
                metadata = PhotoMetadata(
                    // Server-validated dimensions when available, else the (capped) client values.
                    width = dims?.first ?: conf.width.coerceIn(0, MAX_IMAGE_DIMENSION),
                    height = dims?.second ?: conf.height.coerceIn(0, MAX_IMAGE_DIMENSION),
                    takenAt = takenAt,
                    location = if (conf.latitude != null && conf.longitude != null)
                        GeoLocation(conf.latitude, conf.longitude) else null,
                    sharpness = conf.sharpness
                )
            )
        }
        val saved = photoRepository.saveAll(photos)
        logger.info("Confirmed {} uploads for trip {}", saved.size, tripId)
        if (trip.status == TripStatus.CREATED) {
            tripService.updateStatus(tripId, TripStatus.UPLOADING_PHOTOS)
        }
        return saved
    }

    /**
     * Downloads the object and verifies it is a genuine image (of a recognized image format)
     * with safe dimensions — so arbitrary bytes merely labeled as an image can't enter the
     * vision/PDF pipeline. Returns the parsed (width, height) when the format exposes it.
     */
    private fun validateImageObject(storageKey: String): Pair<Int, Int>? {
        val bytes = storageService.load(storageKey)
        val metadata: Metadata = try {
            ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw IllegalArgumentException("Uploaded file is not a valid image")
        }
        val mime = metadata.getFirstDirectoryOfType(FileTypeDirectory::class.java)
            ?.getString(FileTypeDirectory.TAG_DETECTED_FILE_MIME_TYPE)
        require(mime != null && mime.startsWith("image/")) { "Uploaded file is not an image" }

        val dims = readImageDimensions(metadata)
        if (dims != null) {
            val (w, h) = dims
            require(w in 1..MAX_IMAGE_DIMENSION && h in 1..MAX_IMAGE_DIMENSION) { "Image dimensions exceed the allowed maximum" }
            require(w.toLong() * h.toLong() <= MAX_IMAGE_PIXELS) { "Image resolution exceeds the allowed maximum" }
        }
        return dims
    }

    /** Extracts pixel dimensions from parsed image metadata (works across JPEG/PNG/WebP/HEIF). */
    private fun readImageDimensions(metadata: Metadata): Pair<Int, Int>? {
        var width: Int? = null
        var height: Int? = null
        for (directory in metadata.directories) {
            for (tag in directory.tags) {
                val value = runCatching { directory.getInteger(tag.tagType) }.getOrNull()
                if (value == null || value <= 0) continue
                when {
                    width == null && tag.tagName.equals("Image Width", ignoreCase = true) -> width = value
                    height == null && tag.tagName.equals("Image Height", ignoreCase = true) -> height = value
                }
            }
        }
        val w = width
        val h = height
        return if (w != null && h != null) w to h else null
    }

    fun deletePhoto(photoId: UUID, tripId: UUID, userId: UUID) {
        tripService.getTrip(tripId, userId)
        val photo = photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
        storageService.delete(photo.storageKey)
        photo.thumbnailKey?.let { runCatching { storageService.delete(it) } }
        photoRepository.delete(photo)
        logger.info("Deleted photo: {}", photoId)
    }

    private fun convertToJpeg(file: MultipartFile): ByteArray {
        val heicTemp = Files.createTempFile("heic-", ".heic")
        val jpegTemp = Files.createTempFile("conv-", ".jpg")
        try {
            Files.write(heicTemp, file.bytes)

            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            val cmd = if (isMac)
                listOf("sips", "-s", "format", "jpeg", heicTemp.toString(), "--out", jpegTemp.toString())
            else
                listOf("convert", heicTemp.toString(), jpegTemp.toString())
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed || process.exitValue() != 0) {
                val output = process.inputStream.bufferedReader().readText()
                throw IllegalStateException("HEIC conversion failed: $output")
            }

            val jpegBytes = Files.readAllBytes(jpegTemp)
            logger.info("Converted HEIC to JPEG: {} ({}KB → {}KB)",
                file.originalFilename, file.size / 1024, jpegBytes.size / 1024)
            return jpegBytes
        } finally {
            Files.deleteIfExists(heicTemp)
            Files.deleteIfExists(jpegTemp)
        }
    }

    private fun extractMetadata(imageBytes: ByteArray): PhotoMetadata {
        var width = 0
        var height = 0
        var takenAt: java.time.Instant? = null
        var location: GeoLocation? = null

        try {
            val image = ImageIO.read(ByteArrayInputStream(imageBytes))
            if (image != null) {
                width = image.width
                height = image.height
            }
        } catch (e: Exception) {
            logger.warn("Failed to read image dimensions: {}", e.message)
        }

        try {
            val exifMeta = ImageMetadataReader.readMetadata(ByteArrayInputStream(imageBytes))
            val exif = exifMeta.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val date = exif?.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            if (date != null) {
                takenAt = date.toInstant()
                logger.info("Extracted EXIF takenAt: {}", takenAt)
            }
            val gps = exifMeta.getFirstDirectoryOfType(GpsDirectory::class.java)
            val rawGeo = gps?.geoLocation
            if (rawGeo != null) {
                location = GeoLocation(rawGeo.latitude, rawGeo.longitude)
                // Don't log exact coordinates — that's user PII flowing to centralized logs.
                logger.debug("Extracted GPS location for photo")
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract EXIF data: {}", e.message)
        }

        return PhotoMetadata(width = width, height = height, takenAt = takenAt, location = location)
    }
}
