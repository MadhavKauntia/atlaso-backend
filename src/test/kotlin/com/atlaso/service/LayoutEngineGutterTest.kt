package com.atlaso.service

import com.atlaso.application.layout.PhotoGrouper
import com.atlaso.domain.book.Layout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Multi-image layouts get a cream mat around every photo; single-image layouts stay full-bleed.
 * Verifies the [LayoutEngine.getSlotGeometry] insets rather than exact numbers.
 */
class LayoutEngineGutterTest {

    private val engine = LayoutEngine(PhotoGrouper())

    private fun right(pair: Pair<com.atlaso.domain.book.Position, com.atlaso.domain.book.Size>) =
        pair.first.x + pair.second.width
    private fun bottom(pair: Pair<com.atlaso.domain.book.Position, com.atlaso.domain.book.Size>) =
        pair.first.y + pair.second.height

    @Test
    fun `single-image layouts stay full-bleed`() {
        for (layout in listOf(Layout.SINGLE_FULL, Layout.HERO_LANDSCAPE, Layout.DOUBLE_PAGE_FULL_BLEED)) {
            val (pos, size) = engine.getSlotGeometry(layout, 0, 1)
            assertEquals(0.0, pos.x); assertEquals(0.0, pos.y)
            assertEquals(1.0, size.width); assertEquals(1.0, size.height)
        }
    }

    @Test
    fun `four-grid photos are matted inside the page with gaps between them`() {
        val cells = (0..3).map { engine.getSlotGeometry(Layout.FOUR_GRID, it, 4) }

        // Every cell sits within a margin — nothing bleeds to the page edge.
        for ((pos, size) in cells) {
            assertTrue(pos.x > 0.0 && pos.y > 0.0) { "cell touches top/left edge: $pos" }
            assertTrue(pos.x + size.width < 1.0 && pos.y + size.height < 1.0) { "cell touches bottom/right edge" }
        }
        // A real gutter separates the left column's right edge from the right column's left edge.
        val topLeft = cells[0]; val topRight = cells[1]
        assertTrue(topRight.first.x - right(topLeft) > 0.01) { "no horizontal gutter between columns" }
        val bottomLeft = cells[2]
        assertTrue(bottomLeft.first.y - bottom(topLeft) > 0.01) { "no vertical gutter between rows" }
    }

    @Test
    fun `two-vertical photos are separated by a gutter`() {
        val left = engine.getSlotGeometry(Layout.TWO_VERTICAL, 0, 2)
        val rightCell = engine.getSlotGeometry(Layout.TWO_VERTICAL, 1, 2)
        assertTrue(rightCell.first.x - right(left) > 0.01) { "no gutter between side-by-side photos" }
    }
}
