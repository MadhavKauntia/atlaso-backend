package com.atlaso.application.layout

import com.atlaso.domain.book.Layout
import org.slf4j.LoggerFactory

/** A same-slot-count variant of a layout, used to break monotony (pacing, spreads, audit). */
object LayoutSiblings {
    fun sibling(layout: Layout, group: PhotoGroup): Layout? = when (layout) {
        Layout.FOUR_GRID -> Layout.FOUR_MIXED
        Layout.FOUR_MIXED -> Layout.FOUR_GRID
        Layout.TWO_HORIZONTAL -> Layout.TWO_VERTICAL
        Layout.TWO_VERTICAL -> Layout.TWO_HORIZONTAL
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

/**
 * Final structural audit of the assembled book (§18). Runs AFTER page-count normalization.
 * It verifies the hard invariants, applies the safe deterministic fixes it can (breaking
 * same-layout runs), and logs anything it can only flag. Coverage/uniqueness are guaranteed
 * upstream by [PhotoSelector]; this pass is the last guard on page structure.
 */
object FinalAudit {

    private val logger = LoggerFactory.getLogger(FinalAudit::class.java)

    private val VALID_SIZES = setOf(1, 2, 4)
    private const val NEAR_DUP_SPREAD = 0.85

    /** A page whose layout the audit may adjust in place. */
    data class Page(val group: PhotoGroup, var layout: Layout)

    fun audit(pages: MutableList<Page>) {
        if (pages.isEmpty()) return
        val findings = mutableListOf<String>()

        // 1. Only 1/2/4 photos per normal page (§10).
        pages.forEachIndexed { i, p ->
            val n = p.group.photos.size
            if (n !in VALID_SIZES) findings.add("page ${i + 1} has $n photos (expected 1/2/4)")
        }

        // 2. Every source photo used at most once.
        val ids = pages.flatMap { it.group.photos.mapNotNull { ph -> ph.id } }
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (dupes.isNotEmpty()) findings.add("${dupes.size} photo(s) used more than once")

        // 3. The exact same layout at most 2 consecutive pages (§15) — fix in place.
        var runFixes = 0
        for (i in 2 until pages.size) {
            if (pages[i].layout == pages[i - 1].layout && pages[i - 1].layout == pages[i - 2].layout) {
                LayoutSiblings.sibling(pages[i - 1].layout, pages[i - 1].group)?.let {
                    pages[i - 1].layout = it; runFixes++
                }
            }
        }
        if (runFixes > 0) findings.add("broke $runFixes same-layout run(s)")

        // 4. At most one double-page hero (§11).
        val doublePages = pages.count { it.layout == Layout.DOUBLE_PAGE_FULL_BLEED }
        if (doublePages > 1) findings.add("$doublePages double-page heroes (max 1)")

        // 5. No near-duplicate facing/adjacent pages (§13) — flag for visibility.
        var similarAdjacent = 0
        for (i in 1 until pages.size) {
            val sim = PhotoSimilarity.spreadSimilarity(
                pages[i - 1].group.photos, pages[i].group.photos,
                pages[i - 1].group.episodeIndex, pages[i].group.episodeIndex
            )
            if (sim >= NEAR_DUP_SPREAD) similarAdjacent++
        }
        if (similarAdjacent > 0) findings.add("$similarAdjacent adjacent page pair(s) still highly similar")

        if (findings.isEmpty()) {
            logger.info("Final audit passed: {} pages", pages.size)
        } else {
            logger.info("Final audit ({} pages): {}", pages.size, findings.joinToString("; "))
        }
    }
}
