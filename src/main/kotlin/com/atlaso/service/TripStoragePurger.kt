package com.atlaso.service

import com.atlaso.repository.BookRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.UploadGrantRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Collects and deletes every S3 object the app writes for a trip: photo originals + thumbnails,
 * upload-grant reservations, and book cover/interior PDFs. Shared by user-initiated trip deletion
 * and the scheduled orphan cleanup so both stay in sync with the app's key layout.
 */
@Component
class TripStoragePurger(
    private val photoRepository: PhotoRepository,
    private val uploadGrantRepository: UploadGrantRepository,
    private val bookRepository: BookRepository,
    private val storageService: StorageService,
) {
    private val logger = LoggerFactory.getLogger(TripStoragePurger::class.java)

    /** Every S3 key associated with [tripId]. Deduplicated (a consumed grant shares its photo's key). */
    fun keysForTrip(tripId: UUID): Set<String> {
        val keys = linkedSetOf<String>()
        photoRepository.findByTripId(tripId).forEach { p ->
            keys.add(p.storageKey)
            p.thumbnailKey?.let(keys::add)
        }
        uploadGrantRepository.findByTripId(tripId).forEach { g ->
            keys.add(g.storageKey)
            g.thumbnailKey?.let(keys::add)
        }
        bookRepository.findByTripIdOrderByVersionDesc(tripId).forEach { b ->
            val id = b.id ?: return@forEach
            keys.add("pdfs/$id/cover.pdf")
            keys.add("pdfs/$id/photobook.pdf")
        }
        return keys
    }

    /**
     * Best-effort delete of all S3 objects for a trip. Returns true only if every delete succeeded,
     * so callers that must not orphan objects (the scheduled cleanup) can hold the DB rows and retry.
     * S3 deletes are idempotent, so a key that never existed still counts as success.
     */
    fun purge(tripId: UUID, keys: Set<String> = keysForTrip(tripId)): Boolean {
        var allOk = true
        for (key in keys) {
            runCatching { storageService.delete(key) }
                .onFailure {
                    allOk = false
                    logger.warn("Failed to delete S3 object {} for trip {}: {}", key, tripId, it.message)
                }
        }
        return allOk
    }
}
