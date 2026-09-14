package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Splits a trip's photos into natural episodes/events (airport, surfing, dinner, …)
 * using timestamp proximity, location, scene/subject, objects and colour (§4).
 *
 * Shared by [PhotoSelector] (coverage + saturation) and [PhotoGrouper] (page building)
 * so both reason about the same episodes (§19 — reuse episode membership).
 */
object EpisodeDetector {

    // A gap between two consecutive photos starts a new episode when its boundary
    // score clears this. ~0.55 needs a real time gap plus a location/scene change, so
    // photos within one activity stay together while day/place changes split.
    const val EPISODE_THRESHOLD = 0.55

    /** takenAt ascending (nulls last), filename as a stable secondary key. */
    fun sortChronologically(photos: List<Photo>): List<Photo> =
        photos
            .sortedBy { it.originalFilename }
            .sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })

    /**
     * Detects episodes over already chronologically-sorted photos. Returns the photos
     * regrouped into contiguous episodes (episode order = chronological).
     */
    fun detect(sorted: List<Photo>): List<List<Photo>> {
        if (sorted.isEmpty()) return emptyList()
        val episodes = mutableListOf<List<Photo>>()
        var start = 0
        for (i in 0 until sorted.size - 1) {
            if (boundaryScore(sorted[i], sorted[i + 1]) >= EPISODE_THRESHOLD) {
                episodes.add(sorted.subList(start, i + 1))
                start = i + 1
            }
        }
        episodes.add(sorted.subList(start, sorted.size))
        return episodes
    }

    /** Sorts then detects — convenience for callers that hold an unsorted list. */
    fun sortAndDetect(photos: List<Photo>): List<List<Photo>> = detect(sortChronologically(photos))

    /** Calendar day (UTC) of a photo, or null when it has no timestamp. */
    fun dayOf(photo: Photo): LocalDate? =
        photo.metadata.takenAt?.atZone(ZoneOffset.UTC)?.toLocalDate()

    /**
     * How strongly an episode boundary belongs between [a] and [b]; higher = stronger
     * break. Temporal gap 0.40, location mismatch 0.25, scene mismatch 0.20, object
     * dissimilarity 0.10, colour-temperature shift 0.05.
     */
    fun boundaryScore(a: Photo, b: Photo): Double {
        var score = 0.0

        val ta = a.metadata.takenAt
        val tb = b.metadata.takenAt
        if (ta != null && tb != null) {
            val gapMinutes = Duration.between(ta, tb).toMinutes().coerceAtLeast(0).toDouble()
            score += minOf(gapMinutes / 120.0, 1.0) * 0.40
        } else {
            score += 0.20
        }

        val sa = a.signals
        val sb = b.signals
        if (sa != null && sb != null) {
            if (sa.locationTag != null && sb.locationTag != null && sa.locationTag != sb.locationTag) score += 0.25
            if (sa.sceneType != null && sb.sceneType != null && sa.sceneType != sb.sceneType) score += 0.20
            score += (1.0 - PhotoSimilarity.objectJaccard(sa.detectedObjects, sb.detectedObjects)) * 0.10
            if (sa.colorTemperature != sb.colorTemperature) score += 0.05
        }

        return score
    }
}
