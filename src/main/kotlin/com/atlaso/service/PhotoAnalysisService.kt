package com.atlaso.service

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.infrastructure.ai.PhotoAnalysisResponse
import com.atlaso.infrastructure.ai.VisionModelService
import com.atlaso.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

@Service
@Transactional
class PhotoAnalysisService(
    private val photoRepository: PhotoRepository,
    private val storageService: StorageService,
    private val visionModelService: VisionModelService
) {
    private val logger = LoggerFactory.getLogger(PhotoAnalysisService::class.java)
    private val executor = Executors.newFixedThreadPool(10)

    fun analyzeUnanalyzedPhotos(tripId: UUID): List<Photo> {
        val unanalyzed = photoRepository.findByTripIdAndSignalsIsNull(tripId)
        logger.info("Found {} unanalyzed photos for trip {}", unanalyzed.size, tripId)

        val futures = unanalyzed.map { photo ->
            executor.submit<Photo> { analyzePhoto(photo) }
        }
        return futures.map { it.get() }
    }

    fun analyzePhoto(photo: Photo): Photo {
        val imageUrl = storageService.getAccessUrl(photo.storageKey, photo.contentType)
        val response = visionModelService.analyzePhoto(imageUrl)
        val signals = toPhotoSignals(response)

        photo.signals = signals
        photo.analyzedAt = Instant.now()

        val saved = photoRepository.save(photo)
        logger.info("Analyzed photo: {}", saved.id)
        return saved
    }

    private fun toPhotoSignals(response: PhotoAnalysisResponse): PhotoSignals {
        return PhotoSignals(
            sceneType = response.sceneType.name.lowercase(),
            aestheticScore = response.aestheticScore,
            blurScore = response.blurScore,
            timeOfDay = response.timeOfDay.name.lowercase(),
            facesCount = response.facesCount,
            isBlurry = response.blurScore > 0.6,
            dominantColors = response.dominantColors,
            detectedObjects = response.detectedObjects,
            colorTemperature = response.colorTemperature,
            mood = response.mood,
            depthOfField = response.depthOfField,
            locationTag = response.locationTag
        )
    }
}
