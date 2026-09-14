package com.atlaso.service

import com.atlaso.application.layout.FinalAudit
import com.atlaso.application.layout.LayoutSiblings
import com.atlaso.application.layout.Orientation
import com.atlaso.application.layout.PhotoGroup
import com.atlaso.application.layout.PhotoGrouper
import com.atlaso.application.layout.PhotoSimilarity
import com.atlaso.domain.book.Layout
import com.atlaso.domain.book.Page
import com.atlaso.domain.book.PhotoSlot
import com.atlaso.domain.book.Position
import com.atlaso.domain.book.Size
import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LayoutEngine(private val photoGrouper: PhotoGrouper) {

    private val logger = LoggerFactory.getLogger(LayoutEngine::class.java)

    companion object {
        // Single-page trim: 6.9 x 9.8in (portrait).
        private const val PAGE_ASPECT = 6.9 / 9.8
        // Below this cover-fit visible fraction, full-bleed would crop too hard → frame it.
        private const val CROP_SAFE_MIN = 0.62
        // A facing pair at/above this spread similarity is treated as redundant (§13).
        private const val SPREAD_SIM_THRESHOLD = 0.6
    }

    fun generatePages(photos: List<Photo>): List<Page> {
        if (photos.isEmpty()) return emptyList()
        return generatePagesFromGroups(photoGrouper.group(photos))
    }

    private data class Planned(val group: PhotoGroup, var layout: Layout)

    fun generatePagesFromGroups(groups: List<PhotoGroup>): List<Page> {
        if (groups.isEmpty()) return emptyList()

        // 1. Assign an initial layout to each group (prev-aware).
        val planned = mutableListOf<Planned>()
        var prevLayout: Layout? = null
        for (group in groups) {
            if (group.photos.isEmpty()) continue
            val layout = chooseLayout(group, prevLayout)
            planned.add(Planned(group, layout))
            prevLayout = layout
        }

        // 2. Deterministic pacing pass (rhythm: break identical runs, establish/close).
        pacingPass(planned)

        // 3. Spread composition: treat facing pages as one composition and reduce redundancy.
        spreadCompositionPass(planned)

        // 4. Final structural audit (may adjust layouts to enforce invariants).
        val audited = planned.map { FinalAudit.Page(it.group, it.layout) }.toMutableList()
        FinalAudit.audit(audited)

        // 5. Build pages with geometry.
        var pageNumber = 1
        val pages = audited.map { p ->
            createPage(pageNumber++, p.layout, orderSlotsForLayout(p.group.photos, p.layout))
        }

        logger.info("Generated {} pages from {} groups", pages.size, groups.size)
        return pages
    }

    /**
     * Treats LEFT+RIGHT facing pages (pairs 0-1, 2-3, …) as one composition (§12, §13).
     * Where a facing pair is too similar, prefer complementary content by swapping the right
     * page with its same-episode neighbour (a safe reorder — episode order is preserved), and
     * add layout contrast when both pages share a layout. Colour is folded into the spread
     * similarity metric as a light secondary signal (§14).
     */
    private fun spreadCompositionPass(planned: MutableList<Planned>) {
        var k = 0
        while (k + 1 < planned.size) {
            val leftIdx = k
            val rightIdx = k + 1
            val sim = spreadSim(planned[leftIdx], planned[rightIdx])
            if (sim >= SPREAD_SIM_THRESHOLD) {
                // Try swapping the right page with the next page (same episode = safe reorder).
                val nextIdx = k + 2
                if (nextIdx < planned.size && sameEpisode(planned[rightIdx], planned[nextIdx])) {
                    val candidate = planned[nextIdx]
                    if (spreadSim(planned[leftIdx], candidate) < sim) {
                        val tmp = planned[rightIdx]
                        planned[rightIdx] = candidate
                        planned[nextIdx] = tmp
                    }
                }
                // Add layout contrast when the pair still shares a layout.
                if (planned[leftIdx].layout == planned[rightIdx].layout) {
                    LayoutSiblings.sibling(planned[rightIdx].layout, planned[rightIdx].group)
                        ?.let { planned[rightIdx].layout = it }
                }
            }
            k += 2
        }
    }

    private fun spreadSim(a: Planned, b: Planned): Double =
        PhotoSimilarity.spreadSimilarity(
            a.group.photos, b.group.photos, a.group.episodeIndex, b.group.episodeIndex
        )

    private fun sameEpisode(a: Planned, b: Planned): Boolean =
        a.group.episodeIndex >= 0 && a.group.episodeIndex == b.group.episodeIndex

    /**
     * One deterministic pass to vary rhythm without breaking chronology much:
     * break runs of 3 identical layouts by flipping the middle to its sibling
     * variant, and prefer an establishing image first / a quiet image last.
     */
    private fun pacingPass(planned: MutableList<Planned>) {
        // No 3 identical layouts in a row (also softens runs of dense collages).
        for (i in 2 until planned.size) {
            if (planned[i].layout == planned[i - 1].layout && planned[i - 1].layout == planned[i - 2].layout) {
                val sibling = LayoutSiblings.sibling(planned[i - 1].layout, planned[i - 1].group)
                if (sibling != null) planned[i - 1].layout = sibling
            }
        }

        // Opening: prefer an establishing/arrival single at the front.
        if (planned.isNotEmpty() && !isEstablishing(planned[0])) {
            val idx = (1 until minOf(4, planned.size)).firstOrNull { isEstablishing(planned[it]) }
            if (idx != null) planned.add(0, planned.removeAt(idx))
        }

        // Ending: prefer a quiet/scenic single at the end.
        if (planned.size >= 2 && !isQuiet(planned.last())) {
            val from = maxOf(0, planned.size - 4)
            val idx = (from until planned.size - 1).lastOrNull { isQuiet(planned[it]) }
            if (idx != null) planned.add(planned.removeAt(idx))
        }
    }

    private fun isEstablishing(p: Planned): Boolean {
        if (p.group.photos.size != 1) return false
        val s = p.group.photos[0].signals ?: return false
        return s.settingScope == "environment" || s.shotDistance == "wide" ||
            s.subjectType == "landscape" || s.subjectType == "architecture"
    }

    private fun isQuiet(p: Planned): Boolean {
        if (p.group.photos.size != 1) return false
        val s = p.group.photos[0].signals ?: return false
        return s.mood == "serene" || s.shotDistance == "closeup" ||
            s.subjectType == "landscape" || s.settingScope == "environment"
    }

    // ─── Layout choice: count + orientation + standalone + shot/scope + crop + prev ───

    private fun chooseLayout(group: PhotoGroup, prev: Layout?): Layout {
        val photos = group.photos
        // Normal pages are 1/2/4 photos only (§10); grouping never emits 3. A stray other
        // count falls through to the four-photo treatment as a safety net (no THREE_GRID).
        return when (photos.size) {
            1 -> chooseSingle(photos[0], group)
            2 -> chooseTwo(photos)
            else -> chooseFour(photos, group, prev)
        }
    }

    private fun chooseSingle(photo: Photo, group: PhotoGroup): Layout {
        val s = photo.signals
        val landscape = Orientation.from(photo.metadata.width, photo.metadata.height) == Orientation.LANDSCAPE
        val fullBleed = if (landscape) Layout.HERO_LANDSCAPE else Layout.SINGLE_FULL

        // Framing avoids a hard crop, and suits quiet / intimate / breathing-room images.
        if (coverVisibleFraction(photo) < CROP_SAFE_MIN) return Layout.SINGLE_FRAMED
        if (s == null) return fullBleed
        return when {
            s.settingScope == "environment" || s.shotDistance == "wide" -> fullBleed // immersive
            s.shotDistance == "closeup" || s.negativeSpace == "high" -> Layout.SINGLE_FRAMED
            else -> fullBleed
        }
    }

    private fun chooseTwo(photos: List<Photo>): Layout {
        val orientations = photos.map { Orientation.from(it.metadata.width, it.metadata.height) }
        return when {
            orientations.all { it == Orientation.PORTRAIT } -> Layout.TWO_VERTICAL   // two tall halves
            orientations.all { it == Orientation.LANDSCAPE } -> Layout.TWO_HORIZONTAL // two wide halves
            else -> Layout.TWO_HORIZONTAL
        }
    }

    private fun chooseFour(photos: List<Photo>, group: PhotoGroup, prev: Layout?): Layout {
        val scores = photos.map { group.standaloneScores[it.id] ?: 0.0 }.sortedDescending()
        val top = scores.first()
        val restAvg = scores.drop(1).average()
        // One clearly stronger (but not solo-worthy) photo → give it more space.
        val oneStandsOut = top - restAvg >= 0.12 && top >= 0.6
        // Also break up a run of identical grids.
        return if (oneStandsOut || prev == Layout.FOUR_GRID) Layout.FOUR_MIXED else Layout.FOUR_GRID
    }

    /** Fraction of the photo still visible after cover-fitting it to a portrait page. */
    private fun coverVisibleFraction(photo: Photo): Double {
        val w = photo.metadata.width
        val h = photo.metadata.height
        if (w <= 0 || h <= 0) return 1.0
        val photoAspect = w.toDouble() / h
        return minOf(photoAspect, PAGE_ASPECT) / maxOf(photoAspect, PAGE_ASPECT)
    }

    // Photos arrive standalone-desc ordered, so index 0 is the featured image.
    private fun orderSlotsForLayout(photos: List<Photo>, layout: Layout): List<Photo> {
        if (layout == Layout.THREE_GRID && photos.size >= 3) {
            val featured = photos[0]
            // Portraits crop better in the square bottom cells.
            val rest = photos.drop(1).sortedBy {
                if (Orientation.from(it.metadata.width, it.metadata.height) == Orientation.PORTRAIT) 1 else 0
            }
            return listOf(featured) + rest
        }
        return photos
    }

    private fun createPage(pageNumber: Int, layout: Layout, photos: List<Photo>): Page {
        val slots = photos.mapIndexed { index, photo ->
            val (position, size) = getSlotGeometry(layout, index, photos.size)
            PhotoSlot(
                photoId = photo.id!!,
                position = position,
                size = size,
                rotation = photo.rotation
            )
        }
        return Page(pageNumber = pageNumber, layout = layout, slots = slots)
    }

    private fun getSlotGeometry(layout: Layout, index: Int, totalSlots: Int): Pair<Position, Size> {
        return when (layout) {
            Layout.SINGLE_FULL, Layout.HERO_LANDSCAPE, Layout.DOUBLE_PAGE_FULL_BLEED ->
                Pair(Position(0.0, 0.0), Size(1.0, 1.0))

            Layout.SINGLE_FRAMED ->
                Pair(Position(0.10, 0.08), Size(0.80, 0.84)) // generous white margin

            Layout.TWO_HORIZONTAL -> {
                val y = index * 0.5
                Pair(Position(0.0, y), Size(1.0, 0.5))
            }

            Layout.TWO_VERTICAL -> {
                val x = index * 0.5
                Pair(Position(x, 0.0), Size(0.5, 1.0))
            }

            Layout.THREE_GRID -> when (index) {
                0 -> Pair(Position(0.0, 0.0), Size(1.0, 0.5))
                1 -> Pair(Position(0.0, 0.5), Size(0.5, 0.5))
                else -> Pair(Position(0.5, 0.5), Size(0.5, 0.5))
            }

            Layout.FOUR_MIXED -> when (index) {
                0 -> Pair(Position(0.0, 0.0), Size(1.0, 0.6))       // featured top
                1 -> Pair(Position(0.0, 0.6), Size(1.0 / 3, 0.4))
                2 -> Pair(Position(1.0 / 3, 0.6), Size(1.0 / 3, 0.4))
                else -> Pair(Position(2.0 / 3, 0.6), Size(1.0 / 3, 0.4))
            }

            Layout.FOUR_GRID -> {
                val x = (index % 2) * 0.5
                val y = (index / 2) * 0.5
                Pair(Position(x.toDouble(), y.toDouble()), Size(0.5, 0.5))
            }
        }
    }
}
