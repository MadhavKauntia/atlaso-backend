package com.atlaso.service

import com.atlaso.application.layout.Orientation
import com.atlaso.application.layout.PhotoGroup
import com.atlaso.application.layout.PhotoGrouper
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

        // 2. Deterministic pacing pass.
        pacingPass(planned)

        // 3. Build pages with geometry.
        var pageNumber = 1
        val pages = planned.map { p ->
            createPage(pageNumber++, p.layout, orderSlotsForLayout(p.group.photos, p.layout))
        }

        logger.info("Generated {} pages from {} groups", pages.size, groups.size)
        return pages
    }

    /**
     * One deterministic pass to vary rhythm without breaking chronology much:
     * break runs of 3 identical layouts by flipping the middle to its sibling
     * variant, and prefer an establishing image first / a quiet image last.
     */
    private fun pacingPass(planned: MutableList<Planned>) {
        // No 3 identical layouts in a row (also softens runs of dense collages).
        for (i in 2 until planned.size) {
            if (planned[i].layout == planned[i - 1].layout && planned[i - 1].layout == planned[i - 2].layout) {
                val sibling = siblingLayout(planned[i - 1].layout, planned[i - 1].group)
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

    /** A same-slot-count variant of a layout, used to break identical runs. */
    private fun siblingLayout(layout: Layout, group: PhotoGroup): Layout? {
        return when (layout) {
            Layout.FOUR_GRID -> Layout.FOUR_MIXED
            Layout.FOUR_MIXED -> Layout.FOUR_GRID
            // TWO_VERTICAL is disabled, so a two-photo page has no same-count sibling.
            Layout.TWO_HORIZONTAL -> null
            Layout.SINGLE_FULL -> Layout.SINGLE_FRAMED
            Layout.HERO_LANDSCAPE -> Layout.SINGLE_FRAMED
            Layout.SINGLE_FRAMED -> {
                val p = group.photos.firstOrNull()
                when {
                    p == null -> null
                    Orientation.from(p.metadata.width, p.metadata.height) == Orientation.LANDSCAPE -> Layout.HERO_LANDSCAPE
                    else -> Layout.SINGLE_FULL
                }
            }
            else -> null // THREE_GRID / DOUBLE_PAGE have no same-count sibling
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
        return when (photos.size) {
            1 -> chooseSingle(photos[0], group)
            2 -> Layout.TWO_HORIZONTAL // two-photo pages always stack; vertical split disabled
            3 -> Layout.THREE_GRID
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
