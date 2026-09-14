package com.atlaso.service

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.infrastructure.ai.PhotoAnalysisResponse
import com.atlaso.infrastructure.ai.VisionModelService
import com.atlaso.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

@Service
@Transactional
class PhotoAnalysisService(
    private val photoRepository: PhotoRepository,
    private val visionModelService: VisionModelService,
    private val storageService: StorageService,
    // Concurrent vision calls. Effective parallelism = this (calls are synchronous).
    // Ceiling is the OpenAI account tier's requests-per-minute; tune via config.
    @Value("\${openai.api.analysis-concurrency:20}") private val concurrency: Int
) {
    private val logger = LoggerFactory.getLogger(PhotoAnalysisService::class.java)
    private val executor = Executors.newFixedThreadPool(concurrency.coerceIn(1, 64))

    companion object {
        // A "burst" = photos taken within this window of each other. Kept tight so
        // only genuine rapid-fire shots collapse, not distinct moments.
        private const val BURST_WINDOW_SECONDS = 2L
        // Frames kept per burst (the sharpest), so vision still ranks a couple.
        // Clusters of <= this size lose nothing.
        private const val REPS_PER_BURST = 2
    }

    fun analyzeUnanalyzedPhotos(tripId: UUID): List<Photo> {
        val unanalyzed = photoRepository.findByTripIdAndSignalsIsNull(tripId)

        // Skip vision calls for redundant burst frames: cluster tight bursts and
        // analyze only the sharpest representatives. Everything else is analyzed.
        val representatives = selectRepresentatives(unanalyzed)
        logger.info(
            "Trip {}: analyzing {} representatives out of {} unanalyzed photos",
            tripId, representatives.size, unanalyzed.size
        )

        val futures = representatives.map { photo ->
            executor.submit<Photo> { analyzePhoto(photo) }
        }
        return futures.map { it.get() }
    }

    /**
     * Collapses tight time-bursts to their [REPS_PER_BURST] sharpest frames.
     * Photos without a timestamp can't be clustered, so they're all kept.
     */
    private fun selectRepresentatives(photos: List<Photo>): List<Photo> {
        val timed = photos.filter { it.metadata.takenAt != null }.sortedBy { it.metadata.takenAt }
        val untimed = photos.filter { it.metadata.takenAt == null }

        val kept = mutableListOf<Photo>()
        kept.addAll(untimed)

        var cluster = mutableListOf<Photo>()
        var clusterStart: Instant? = null

        fun flush() {
            if (cluster.isEmpty()) return
            if (cluster.size <= REPS_PER_BURST) {
                kept.addAll(cluster)
            } else {
                kept.addAll(cluster.sortedByDescending { it.metadata.sharpness ?: 0.0 }.take(REPS_PER_BURST))
            }
            cluster = mutableListOf()
        }

        for (photo in timed) {
            val t = photo.metadata.takenAt!!
            if (clusterStart == null || Duration.between(clusterStart, t).seconds <= BURST_WINDOW_SECONDS) {
                if (clusterStart == null) clusterStart = t
                cluster.add(photo)
            } else {
                flush()
                clusterStart = t
                cluster.add(photo)
            }
        }
        flush()
        return kept
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
            locationTag = response.locationTag,
            subjectType = response.subjectType,
            shotDistance = response.shotDistance,
            subjectProminence = response.subjectProminence,
            settingScope = response.settingScope,
            backgroundComplexity = response.backgroundComplexity,
            negativeSpace = response.negativeSpace
        )
    }
}
