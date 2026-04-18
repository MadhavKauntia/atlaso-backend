package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.*

data class PhotoGroup(
    val photos: List<Photo>,
    val representativeTime: Instant?
)

@Component
class PhotoGrouper {

    private val logger = LoggerFactory.getLogger(PhotoGrouper::class.java)

    private val sceneCompatibility: Map<Pair<String, String>, Double> = buildMap {
        fun put(a: String, b: String, score: Double) {
            put(a to b, score)
            put(b to a, score)
        }
        put("people", "people", 1.0)
        put("landscape", "landscape", 1.0)
        put("city", "city", 1.0)
        put("food", "food", 1.0)
        put("misc", "misc", 1.0)
        put("people", "food", 0.7)
        put("people", "city", 0.6)
        put("city", "food", 0.6)
        put("people", "misc", 0.5)
        put("city", "misc", 0.5)
        put("landscape", "city", 0.4)
        put("food", "misc", 0.4)
        put("people", "landscape", 0.3)
        put("landscape", "misc", 0.3)
        put("landscape", "food", 0.1)
    }

    fun group(photos: List<Photo>): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        val remaining = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt }).toMutableList()
        val groups = mutableListOf<PhotoGroup>()

        while (remaining.isNotEmpty()) {
            val anchor = remaining.removeFirst()
            val currentGroup = mutableListOf(anchor)

            val candidates = remaining.sortedByDescending { similarity(anchor, it) }
            for (candidate in candidates) {
                if (currentGroup.size >= 4) break
                val avgSim = currentGroup.map { similarity(candidate, it) }.average()
                if (avgSim >= 0.55) {
                    currentGroup.add(candidate)
                }
            }
            currentGroup.forEach { remaining.remove(it) }

            val sorted = currentGroup.sortedByDescending { it.signals?.aestheticScore ?: 0.0 }
            val repTime = medianTime(sorted)
            groups.add(PhotoGroup(photos = sorted, representativeTime = repTime))
        }

        val ordered = groups.sortedWith(compareBy(nullsLast()) { it.representativeTime })
        return mergeTinyGroups(ordered)
    }

    private fun similarity(a: Photo, b: Photo): Double {
        val colorScore = colorHarmonyScore(
            a.signals?.dominantColors ?: emptyList(),
            b.signals?.dominantColors ?: emptyList(),
            a.signals?.colorTemperature ?: "neutral",
            b.signals?.colorTemperature ?: "neutral"
        )
        val timeScore = timeOfDayScore(
            a.signals?.timeOfDay ?: "day",
            b.signals?.timeOfDay ?: "day"
        )
        val objectScore = objectOverlapScore(
            a.signals?.detectedObjects ?: emptyList(),
            b.signals?.detectedObjects ?: emptyList()
        )
        val sceneScore = sceneCompatibilityScore(
            a.signals?.sceneType ?: "misc",
            b.signals?.sceneType ?: "misc"
        )
        val temporalScore = temporalProximityScore(a.metadata.takenAt, b.metadata.takenAt)

        return colorScore * 0.25 +
                timeScore * 0.20 +
                objectScore * 0.20 +
                sceneScore * 0.20 +
                temporalScore * 0.15
    }

    private fun colorHarmonyScore(
        colors1: List<String>,
        colors2: List<String>,
        temp1: String,
        temp2: String
    ): Double {
        val h1 = colors1.firstOrNull()?.let { hexToHue(it) }
        val h2 = colors2.firstOrNull()?.let { hexToHue(it) }

        val hueScore = if (h1 != null && h2 != null) {
            val dist = min(abs(h1 - h2), 360.0 - abs(h1 - h2))
            when {
                dist <= 15.0 -> 1.0
                dist <= 25.0 -> 0.8
                dist <= 40.0 -> 0.5
                dist <= 55.0 -> 0.2
                else -> 0.1
            }
        } else {
            0.5
        }

        val tempBonus = when {
            temp1 == temp2 -> 0.1
            (temp1 == "warm" && temp2 == "cool") || (temp1 == "cool" && temp2 == "warm") -> -0.1
            else -> 0.0
        }

        return (hueScore + tempBonus).coerceIn(0.0, 1.0)
    }

    private fun hexToHue(hex: String): Double? {
        val cleaned = hex.trimStart('#')
        if (cleaned.length != 6) return null
        return try {
            val value = cleaned.toInt(16)
            val r = ((value shr 16) and 0xFF) / 255.0
            val g = ((value shr 8) and 0xFF) / 255.0
            val b = (value and 0xFF) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            if (delta == 0.0) return 0.0
            val h = when (max) {
                r -> ((g - b) / delta).mod(6.0)
                g -> (b - r) / delta + 2.0
                else -> (r - g) / delta + 4.0
            }
            (h * 60.0).mod(360.0)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun timeOfDayScore(a: String, b: String): Double {
        if (a == b) return 1.0
        val adjacent = setOf("day" to "golden_hour", "golden_hour" to "day", "golden_hour" to "night", "night" to "golden_hour")
        return if ((a to b) in adjacent) 0.5 else 0.0
    }

    private fun objectOverlapScore(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.3
        val normalize = { s: String -> s.trimEnd('s').lowercase() }
        val setA = a.map(normalize).toSet()
        val setB = b.map(normalize).toSet()
        val intersection = (setA intersect setB).size
        val union = (setA union setB).size
        return if (union == 0) 0.3 else intersection.toDouble() / union
    }

    private fun sceneCompatibilityScore(a: String, b: String): Double {
        return sceneCompatibility[a to b] ?: 0.3
    }

    private fun temporalProximityScore(a: Instant?, b: Instant?): Double {
        if (a == null || b == null) return 0.4
        val gapMinutes = abs(a.epochSecond - b.epochSecond) / 60.0
        return exp(-gapMinutes / 15.0)
    }

    private fun medianTime(photos: List<Photo>): Instant? {
        val times = photos.mapNotNull { it.metadata.takenAt }.sorted()
        if (times.isEmpty()) return null
        return times[times.size / 2]
    }

    private fun mergeTinyGroups(groups: List<PhotoGroup>): List<PhotoGroup> {
        if (groups.size <= 1) return groups

        val result = groups.toMutableList()
        var i = 0
        while (i < result.size) {
            val group = result[i]
            if (group.photos.size == 1) {
                val neighbor = findMergeCandidate(result, i)
                if (neighbor != null) {
                    val (ni, neighborGroup) = neighbor
                    val merged = PhotoGroup(
                        photos = (neighborGroup.photos + group.photos)
                            .sortedByDescending { it.signals?.aestheticScore ?: 0.0 },
                        representativeTime = medianTime(neighborGroup.photos + group.photos)
                    )
                    result[ni] = merged
                    result.removeAt(i)
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun findMergeCandidate(
        groups: MutableList<PhotoGroup>,
        idx: Int
    ): Pair<Int, PhotoGroup>? {
        val solo = groups[idx]
        val soloTime = solo.representativeTime

        val neighbors = listOfNotNull(
            if (idx > 0) idx - 1 to groups[idx - 1] else null,
            if (idx < groups.size - 1) idx + 1 to groups[idx + 1] else null
        )

        for ((ni, neighbor) in neighbors) {
            if (neighbor.photos.size >= 4) continue
            val neighborTime = neighbor.representativeTime
            if (soloTime != null && neighborTime != null) {
                val gapHours = abs(soloTime.epochSecond - neighborTime.epochSecond) / 3600.0
                if (gapHours > 3.0) continue
            }
            val neighborScore = neighbor.photos.map { similarity(solo.photos.first(), it) }.average()
            if (neighborScore >= 0.4) return ni to neighbor
        }
        return null
    }
}
