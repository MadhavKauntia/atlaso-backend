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

    fun generatePages(photos: List<Photo>): List<Page> {
        if (photos.isEmpty()) return emptyList()
        return generatePagesFromGroups(photoGrouper.group(photos))
    }

    fun generatePagesFromGroups(groups: List<PhotoGroup>): List<Page> {
        if (groups.isEmpty()) return emptyList()

        val pages = mutableListOf<Page>()
        var pageNumber = 1

        for (group in groups) {
            if (group.photos.isEmpty()) continue
            val layout = chooseLayout(group)
            val ordered = orderSlotsForLayout(group.photos, layout)
            pages.add(createPage(pageNumber++, layout, ordered))
        }

        logger.info("Generated {} pages from {} groups", pages.size, groups.size)
        return pages
    }

    private fun chooseLayout(group: PhotoGroup): Layout {
        val photos = group.photos
        return when (photos.size) {
            1 -> {
                val o = Orientation.from(photos[0].metadata.width, photos[0].metadata.height)
                if (o == Orientation.LANDSCAPE) Layout.HERO_LANDSCAPE else Layout.SINGLE_FULL
            }
            2 -> Layout.TWO_HORIZONTAL
            3 -> Layout.THREE_GRID
            else -> Layout.FOUR_GRID
        }
    }

    // For THREE_GRID: put the best "featured" photo at index 0 (full-width top slot).
    // Photos from the grouper are already sorted by aestheticScore desc, but face count and
    // depth-of-field can override which photo belongs in the hero position.
    private fun orderSlotsForLayout(photos: List<Photo>, layout: Layout): List<Photo> {
        if (layout != Layout.THREE_GRID || photos.size < 3) return photos

        val ranked = photos.sortedByDescending { featuredScore(it) }
        val featured = ranked[0]
        val rest = ranked.drop(1)

        // Portrait photos crop better in the square bottom cells; put portrait at slot 2 (right).
        val bottomOrdered = rest.sortedBy { photo ->
            val o = Orientation.from(photo.metadata.width, photo.metadata.height)
            if (o == Orientation.PORTRAIT) 1 else 0
        }
        return listOf(featured) + bottomOrdered
    }

    private fun featuredScore(photo: Photo): Double {
        val s = photo.signals ?: return 0.0
        return s.aestheticScore * 0.5 +
                (if (s.facesCount > 0) 0.2 else 0.0) +
                (if (s.depthOfField == "shallow") 0.15 else 0.0) +
                (if (s.timeOfDay == "golden_hour") 0.1 else 0.0) +
                (if (Orientation.from(photo.metadata.width, photo.metadata.height) == Orientation.LANDSCAPE) 0.05 else 0.0)
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
        return Page(
            pageNumber = pageNumber,
            layout = layout,
            slots = slots
        )
    }

    private fun getSlotGeometry(layout: Layout, index: Int, totalSlots: Int): Pair<Position, Size> {
        return when (layout) {
            Layout.SINGLE_FULL, Layout.HERO_LANDSCAPE -> {
                Pair(Position(0.0, 0.0), Size(1.0, 1.0))
            }
            Layout.TWO_HORIZONTAL -> {
                val y = index * 0.5
                Pair(Position(0.0, y), Size(1.0, 0.5))
            }
            Layout.THREE_GRID -> when (index) {
                0 -> Pair(Position(0.0, 0.0), Size(1.0, 0.5))
                1 -> Pair(Position(0.0, 0.5), Size(0.5, 0.5))
                else -> Pair(Position(0.5, 0.5), Size(0.5, 0.5))
            }
            Layout.FOUR_GRID -> {
                val x = (index % 2) * 0.5
                val y = (index / 2) * 0.5
                Pair(Position(x.toDouble(), y.toDouble()), Size(0.5, 0.5))
            }
        }
    }
}
