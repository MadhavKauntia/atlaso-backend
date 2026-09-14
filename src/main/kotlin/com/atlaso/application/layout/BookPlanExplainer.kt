package com.atlaso.application.layout

import com.atlaso.domain.book.Layout
import com.atlaso.domain.book.Page
import com.atlaso.domain.photo.Photo
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Renders a generated book as a readable, spread-by-spread text plan so the layout can
 * be eyeballed without the frontend. The header is a compliance report for the pacing
 * rules the layout engine enforces:
 *   1. at most one spread with two dense (3-4 photo) pages,
 *   2. no layout repeated on more than 2 consecutive pages,
 *   6. more full-bleed singles than grids,
 *   8. no more than 2 consecutive spreads without people,
 *   4. pages stay largely chronological.
 *
 * Spread model mirrors LayoutEngine: page 1 is a lone recto; facing spreads follow as
 * (2,3), (4,5), …
 */
@Component
class BookPlanExplainer {

    private companion object {
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneOffset.UTC)
        val FULL_BLEED = setOf(Layout.SINGLE_FULL, Layout.HERO_LANDSCAPE, Layout.DOUBLE_PAGE_FULL_BLEED)
        val GRID = setOf(Layout.THREE_GRID, Layout.FOUR_GRID)
    }

    fun explain(pages: List<Page>, photosById: Map<UUID, Photo>): String {
        if (pages.isEmpty()) return "BOOK PLAN — empty (no pages)\n"

        val ordered = pages.sortedBy { it.pageNumber }
        val spreads = toSpreads(ordered)

        return buildString {
            appendLine(header(ordered, spreads, photosById))
            appendLine()
            spreads.forEach { spread -> append(renderSpread(spread, photosById)) }
        }
    }

    // ─── header / compliance report ────────────────────────────────────────────

    private fun header(pages: List<Page>, spreads: List<Spread>, photosById: Map<UUID, Photo>): String {
        val denseDense = spreads.count { it.pages.size == 2 && it.pages.all(::isDense) }
        val maxRun = maxConsecutiveSameLayout(pages)
        val peoplelessRun = maxPeoplelessSpreadRun(spreads, photosById)
        val fullBleed = pages.count { it.layout in FULL_BLEED }
        val grids = pages.count { it.layout in GRID }
        val chronological = isChronological(pages, photosById)

        val mix = pages.groupingBy { it.layout }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString("  ") { "${it.key}×${it.value}" }

        fun check(ok: Boolean) = if (ok) "OK " else "!! "

        return buildString {
            appendLine("════════ BOOK PLAN — ${pages.size} pages / ${spreads.size} spreads ════════")
            appendLine()
            appendLine("RULE CHECK")
            appendLine("  ${check(denseDense <= 1)}dense-dense spreads : $denseDense  (max 1)")
            appendLine("  ${check(maxRun <= 2)}max same-layout run : $maxRun  (max 2)")
            appendLine("  ${check(peoplelessRun <= 2)}people cadence      : longest people-less run $peoplelessRun spread(s) (max 2)")
            appendLine("  ${check(fullBleed >= grids)}full-bleed vs grid  : $fullBleed full-bleed / $grids grid pages")
            appendLine("  ${check(chronological)}chronology          : ${if (chronological) "monotonic" else "OUT OF ORDER"}")
            appendLine()
            append("  layout mix: $mix")
        }
    }

    // ─── per-spread rendering ──────────────────────────────────────────────────

    private fun renderSpread(spread: Spread, photosById: Map<UUID, Photo>): String = buildString {
        if (spread.index == 0) {
            appendLine("─ Page ${spread.pages[0].pageNumber} (recto, stands alone) ─────────────────────")
            append(renderPage(spread.pages[0], null, photosById))
        } else {
            appendLine("─ Spread ${spread.index}  [pages ${spread.pages.first().pageNumber}–${spread.pages.last().pageNumber}] ─────────────")
            spread.pages.forEachIndexed { i, page ->
                append(renderPage(page, if (i == 0) "L" else "R", photosById))
            }
            if (spread.pages.size == 2 && spread.pages.all(::isDense)) {
                appendLine("  ⚠ DENSE-DENSE spread (two collages facing)")
            }
        }
        appendLine()
    }

    private fun renderPage(page: Page, side: String?, photosById: Map<UUID, Photo>): String = buildString {
        val flags = buildList {
            if (isDense(page)) add("DENSE")
            if (isPeoplePage(page, photosById)) add("PEOPLE")
        }.joinToString(" ") { "[$it]" }
        val prefix = side?.let { "$it " } ?: "  "
        appendLine("  $prefix${"Page " + page.pageNumber} · ${page.layout}${if (flags.isEmpty()) "" else "  $flags"}")
        page.slots.forEach { slot ->
            appendLine("       ${photoLine(photosById[slot.photoId])}")
        }
    }

    private fun photoLine(photo: Photo?): String {
        if (photo == null) return "(missing photo)"
        val s = photo.signals
        val subject = s?.let { "${it.subjectType}/${it.shotDistance}" } ?: "unanalyzed"
        val faces = if ((s?.facesCount ?: 0) > 0) " faces=${s!!.facesCount}" else ""
        val time = s?.timeOfDay?.let { " $it" } ?: ""
        val color = s?.dominantColors?.firstOrNull()?.let { " $it" } ?: ""
        val takenAt = photo.metadata.takenAt?.let { " · ${TIME_FMT.format(it)}" } ?: ""
        return "${photo.originalFilename.padEnd(20)} $subject$faces$time$color$takenAt"
    }

    // ─── spread model + metrics (mirror LayoutEngine) ──────────────────────────

    private data class Spread(val index: Int, val pages: List<Page>)

    /** page 1 is its own spread (index 0); the rest pair into (2,3),(4,5),… */
    private fun toSpreads(ordered: List<Page>): List<Spread> {
        val spreads = mutableListOf<Spread>()
        spreads.add(Spread(0, listOf(ordered[0])))
        var i = 1
        var idx = 1
        while (i < ordered.size) {
            spreads.add(Spread(idx, ordered.subList(i, minOf(i + 2, ordered.size))))
            i += 2
            idx++
        }
        return spreads
    }

    private fun isDense(page: Page): Boolean = page.slots.size >= 3

    private fun isPeoplePage(page: Page, photosById: Map<UUID, Photo>): Boolean =
        page.slots.any { slot ->
            val s = photosById[slot.photoId]?.signals ?: return@any false
            s.facesCount >= 1 || s.subjectType == "person" || s.subjectType == "couple" || s.subjectType == "group"
        }

    private fun maxConsecutiveSameLayout(pages: List<Page>): Int {
        var max = 0; var run = 0; var prev: Layout? = null
        for (p in pages) {
            run = if (p.layout == prev) run + 1 else 1
            prev = p.layout
            if (run > max) max = run
        }
        return max
    }

    private fun maxPeoplelessSpreadRun(spreads: List<Spread>, photosById: Map<UUID, Photo>): Int {
        var max = 0; var run = 0
        for (spread in spreads) {
            val hasPeople = spread.pages.any { isPeoplePage(it, photosById) }
            run = if (hasPeople) 0 else run + 1
            if (run > max) max = run
        }
        return max
    }

    /** True when each page's earliest capture time is ≥ the previous page's (nulls ignored). */
    private fun isChronological(pages: List<Page>, photosById: Map<UUID, Photo>): Boolean {
        var last: java.time.Instant? = null
        for (page in pages) {
            val t = page.slots.mapNotNull { photosById[it.photoId]?.metadata?.takenAt }.minOrNull() ?: continue
            if (last != null && t.isBefore(last)) return false
            last = t
        }
        return true
    }
}
