package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoSignals
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Single home for photo-to-photo similarity so selection, grouping, uniqueness and
 * spread evaluation all agree (previously each file had its own copy). All functions
 * are pure and deterministic; pass a [Cache] where the same pairs are compared
 * repeatedly (§19 — cache rather than recompute).
 */
object PhotoSimilarity {

    /** Memoizes pairwise photo similarity within a single generation run (single-threaded). */
    class Cache {
        private val memo = HashMap<Long, Double>()
        fun similarity(a: Photo, b: Photo): Double {
            val ka = a.id
            val kb = b.id
            if (ka == null || kb == null) return PhotoSimilarity.similarity(a, b)
            val key = pairKey(ka, kb)
            return memo.getOrPut(key) { PhotoSimilarity.similarity(a, b) }
        }

        private fun pairKey(a: UUID, b: UUID): Long {
            val ha = a.hashCode().toLong() and 0xFFFFFFFFL
            val hb = b.hashCode().toLong() and 0xFFFFFFFFL
            return if (ha <= hb) (ha shl 32) or hb else (hb shl 32) or ha
        }
    }

    /**
     * Composition similarity of two photos, 0.0 (unrelated) .. 1.0 (near-identical).
     * Same weights used by uniqueness and near-duplicate clustering.
     */
    fun similarity(a: Photo, b: Photo): Double {
        val sa = a.signals ?: return 0.0
        val sb = b.signals ?: return 0.0
        var sim = 0.0
        if (sa.subjectType == sb.subjectType) sim += 0.35
        if (sa.shotDistance == sb.shotDistance) sim += 0.25
        if (sa.settingScope == sb.settingScope) sim += 0.15
        sim += objectJaccard(sa.detectedObjects, sb.detectedObjects) * 0.25
        return sim.coerceIn(0.0, 1.0)
    }

    fun objectJaccard(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else sa.intersect(sb).size / union
    }

    /**
     * How similar two facing pages are (§13). High similarity across several
     * dimensions (same episode, role, subject, shot distance, objects, colour) is
     * redundant; colour similarity alone is fine, so colour is weighted lightly.
     * Returns 0.0 (complementary) .. 1.0 (redundant).
     */
    fun spreadSimilarity(
        left: List<Photo>,
        right: List<Photo>,
        leftEpisode: Int?,
        rightEpisode: Int?
    ): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val ls = left.mapNotNull { it.signals }
        val rs = right.mapNotNull { it.signals }
        if (ls.isEmpty() || rs.isEmpty()) return 0.0

        var score = 0.0
        // Same episode → likely the same moment on both pages.
        if (leftEpisode != null && rightEpisode != null && leftEpisode == rightEpisode) score += 0.20

        // Dominant visual role shared across the two pages.
        val lRole = dominantRole(left)
        val rRole = dominantRole(right)
        if (lRole == rRole) score += 0.25

        // Subject / shot-distance agreement (modal value of each page).
        if (modal(ls) { it.subjectType } == modal(rs) { it.subjectType }) score += 0.15
        if (modal(ls) { it.shotDistance } == modal(rs) { it.shotDistance }) score += 0.10

        // Object overlap across all photos on both pages.
        val lObjects = ls.flatMap { it.detectedObjects }
        val rObjects = rs.flatMap { it.detectedObjects }
        score += objectJaccard(lObjects, rObjects) * 0.15

        // Colour is a light, secondary signal (§14): both palette closeness and temp.
        score += colorSimilarity(ls, rs) * 0.15

        return score.coerceIn(0.0, 1.0)
    }

    private fun dominantRole(photos: List<Photo>): VisualRole =
        photos.map { VisualRole.of(it) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: VisualRole.DETAIL_OBJECT

    private fun <T> modal(list: List<PhotoSignals>, selector: (PhotoSignals) -> T): T? =
        list.map(selector).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Palette + temperature closeness, 0..1. */
    fun colorSimilarity(ls: List<PhotoSignals>, rs: List<PhotoSignals>): Double {
        val tempMatch = if (modal(ls) { it.colorTemperature } == modal(rs) { it.colorTemperature }) 1.0 else 0.0
        val lHues = ls.flatMap { it.dominantColors }.mapNotNull { hueOf(it) }
        val rHues = rs.flatMap { it.dominantColors }.mapNotNull { hueOf(it) }
        val hueMatch = if (lHues.isEmpty() || rHues.isEmpty()) 0.5 else {
            val lAvg = lHues.average()
            val rAvg = rHues.average()
            1.0 - circularHueDistance(lAvg, rAvg) / 180.0
        }
        return (tempMatch * 0.5 + hueMatch * 0.5).coerceIn(0.0, 1.0)
    }

    private fun circularHueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }

    /** Parses a "#rrggbb" hex string to its hue (0..360), or null if unparseable. */
    private fun hueOf(hex: String): Double? {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6) return null
        val r = h.substring(0, 2).toIntOrNull(16) ?: return null
        val g = h.substring(2, 4).toIntOrNull(16) ?: return null
        val b = h.substring(4, 6).toIntOrNull(16) ?: return null
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val max = maxOf(rf, gf, bf); val minc = minOf(rf, gf, bf)
        val delta = max - minc
        if (delta == 0.0) return 0.0
        val hue = when (max) {
            rf -> 60 * (((gf - bf) / delta) % 6)
            gf -> 60 * (((bf - rf) / delta) + 2)
            else -> 60 * (((rf - gf) / delta) + 4)
        }
        return (hue + 360) % 360
    }
}
