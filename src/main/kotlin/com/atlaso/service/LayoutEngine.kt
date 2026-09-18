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
        // Bookend passes scan this many pages from each end — wider than a single spread so the
        // best opener/closer isn't missed (bookends matter enough to bend strict chronology).
        private const val OPENING_WINDOW = 8
        private const val CLOSING_WINDOW = 8
        // A single below this keepsake score is too weak to sit in the opening spread.
        private const val OPENING_KEEPSAKE_FLOOR = 0.5
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
     * A sequence of deterministic, chronology-preserving passes that shape the book's
     * rhythm. Each pass only makes local moves (windowed swaps or in-place layout-variant
     * flips), so pages stay largely in chronological order (req 4). Order matters:
     * variety first, then narrative bookends, then people cadence, then the anti-clutter
     * density cap, then a final in-place variety touch-up so nothing regresses.
     *
     * Spread model: page 1 is a lone recto; facing spreads are then (2,3), (4,5), …
     * i.e. planned index 0 is alone, and indices pair up as (1,2), (3,4), (5,6), …
     */
    private fun pacingPass(planned: MutableList<Planned>) {
        // Req 2: no layout repeated on more than 2 consecutive pages (windowed reorder).
        breakLayoutRuns(planned, allowSwap = true)

        // Opening bookend (#1,#4): lead with the STRONGEST establishing/arrival single — scanning
        // a wide early window, never an object/misc shot. The cover follows page 1, so this also
        // curates the cover.
        openWithStrongestEstablishing(planned)

        // Opening bookend (#2): keep the first three pages clean — swap out any low-narrative
        // object/misc or weak-keepsake single (a stray souvenir shot is most jarring up front).
        curateOpeningPages(planned)

        // People early: establish who took the trip. Guarantee a page featuring people
        // within the first three, pulling the earliest one up if none is present.
        ensurePeopleEarly(planned)

        // Req 8: never go more than 2 spreads without a people photo.
        ensurePeopleEveryTwoSpreads(planned)

        // Req 1: at most one spread with two dense (3-4 photo) pages; otherwise pair a
        // dense page with a solo so facing collages don't clutter the spread.
        capDenseSpreads(planned)

        // Req B: pull same-event pairs onto a single spread so events aren't split by a turn.
        avoidEventTurnBreaks(planned)

        // Closing bookend (#3,#4): end on a calm single — guarantee the last page is a single and
        // prefer the strongest quiet/scenic shot from a wide tail window. Runs after the reorder
        // passes so it has the final say on the last page.
        curateClosingPage(planned)

        // Req 2 (touch-up): reordering above may have re-created a run of identical
        // layouts. Break any remaining runs in place (layout-variant flips only) so we
        // don't undo the density pairing.
        breakLayoutRuns(planned, allowSwap = false)
    }

    // ─── Spread geometry ─────────────────────────────────────────────────────────
    // planned index → page number is (index + 1). Page 1 is a lone recto; the rest
    // pair into facing spreads (2,3), (4,5), …

    /** The spread a 0-based page index belongs to (page 1 / index 0 is its own spread). */
    private fun spreadOf(index: Int): Int = if (index <= 0) 0 else 1 + (index - 1) / 2

    /** The facing-page partner index within the same spread, or null (lone recto). */
    private fun spreadPartner(index: Int): Int? = when {
        index <= 0 -> null            // page 1 stands alone
        index % 2 == 1 -> index + 1   // left page → right partner
        else -> index - 1             // right page → left partner
    }

    /** A "dense" page carries a 3- or 4-photo collage. */
    private fun isDense(p: Planned): Boolean = p.group.photos.size >= 3

    // ─── Event coherence (req B) ───────────────────────────────────────────────
    // Pages carry the episode (event) they came from. Pacing swaps must not tear a
    // multi-page event apart, and a small event should not straddle a page turn.

    private fun eventOf(p: Planned): Int = p.group.eventIndex

    /**
     * True when the page at [i] is alone in its event within the current order — either its
     * event is unknown (-1, e.g. test groups) or neither neighbour shares its event. Only
     * singleton pages may be freely moved by pacing; moving a page out of a multi-page event
     * would split it across the book, which req B forbids.
     */
    private fun isMovable(planned: List<Planned>, i: Int): Boolean {
        val e = eventOf(planned[i])
        if (e < 0) return true
        val leftSame = i > 0 && eventOf(planned[i - 1]) == e
        val rightSame = i < planned.size - 1 && eventOf(planned[i + 1]) == e
        return !leftSame && !rightSame
    }

    /**
     * Req B: keep a same-event pair inside one spread. A 2-page event whose left page sits
     * at an even index straddles a page turn (turns fall after pages 1,3,5… — even indices).
     * When the page just before it is freely movable, rotate that page to just after the
     * event so the pair shifts onto a single spread. Best-effort, one forward pass.
     */
    private fun avoidEventTurnBreaks(planned: MutableList<Planned>) {
        var i = 0
        while (i < planned.size) {
            val e = eventOf(planned[i])
            if (e < 0) { i++; continue }
            var end = i
            while (end + 1 < planned.size && eventOf(planned[end + 1]) == e) end++
            val size = end - i + 1
            // A 2-page event starting on an even index (>0) is split by the turn after it.
            if (size == 2 && i > 0 && i % 2 == 0 && isMovable(planned, i - 1) &&
                eventOf(planned[i - 1]) != e
            ) {
                val before = planned.removeAt(i - 1)
                planned.add(i + 1, before) // event now starts at i-1 (odd) → one spread
            }
            i = end + 1
        }
    }

    /**
     * Req 2: break any run of 3+ identical layouts. First try flipping the middle page
     * to a same-photo-count layout variant (e.g. SINGLE_FULL ↔ SINGLE_FRAMED). When no
     * variant exists (grids, two-ups) and [allowSwap] is set, swap the offending page
     * with a nearby page of a different layout, preserving chronology as much as possible.
     */
    private fun breakLayoutRuns(planned: MutableList<Planned>, allowSwap: Boolean) {
        for (i in 2 until planned.size) {
            if (planned[i].layout != planned[i - 1].layout || planned[i - 1].layout != planned[i - 2].layout) continue

            val sibling = siblingLayout(planned[i - 1].layout, planned[i - 1].group)
            if (sibling != null) {
                planned[i - 1].layout = sibling
                continue
            }
            if (!allowSwap || !isMovable(planned, i)) continue

            // Pull the nearest later movable page with a different layout into position i.
            val j = (i + 1 until minOf(i + 4, planned.size))
                .firstOrNull { planned[it].layout != planned[i].layout && isMovable(planned, it) }
            if (j != null) {
                val tmp = planned[i]; planned[i] = planned[j]; planned[j] = tmp
            }
        }
    }

    /**
     * Req 8: guarantee a people photo at least every two spreads. Walking spreads in
     * order, if a third consecutive spread would have no people, swap a people page from
     * a later spread into it. Best-effort — if no later people page exists we leave it.
     */
    private fun ensurePeopleEveryTwoSpreads(planned: MutableList<Planned>) {
        if (planned.size < 2) return
        val spreads = LinkedHashMap<Int, MutableList<Int>>()
        planned.indices.forEach { i -> spreads.getOrPut(spreadOf(i)) { mutableListOf() }.add(i) }

        var peoplelessRun = 0
        for (key in spreads.keys.sorted()) {
            val pagesInSpread = spreads[key]!!
            if (pagesInSpread.any { isPeoplePage(planned[it]) }) {
                peoplelessRun = 0
                continue
            }
            peoplelessRun++
            if (peoplelessRun <= 2) continue

            // Only borrow a movable people page, into a movable slot, so no event is split.
            val target = pagesInSpread.last()
            if (!isMovable(planned, target)) continue
            val donor = (target + 1 until planned.size).firstOrNull { isPeoplePage(planned[it]) && isMovable(planned, it) }
            if (donor != null) {
                val tmp = planned[target]; planned[target] = planned[donor]; planned[donor] = tmp
                peoplelessRun = 0
            }
        }
    }

    /**
     * Req 1: a spread with two dense (3-4 photo) pages is cluttered. Allow at most one
     * such spread in the whole book; for any beyond that, swap the right dense page with
     * a nearby solo page so each dense collage faces a calmer solo. Best-effort within a
     * small window to keep chronology intact.
     */
    private fun capDenseSpreads(planned: MutableList<Planned>) {
        var denseSpreadBudget = 1
        var left = 1 // first left-of-spread page is index 1 (page 2); left pages are odd indices
        while (left + 1 < planned.size) {
            if (isDense(planned[left]) && isDense(planned[left + 1])) {
                if (denseSpreadBudget > 0) {
                    denseSpreadBudget--
                } else if (isMovable(planned, left + 1)) {
                    // Only relieve the spread when the dense page can move without splitting
                    // its event; otherwise event coherence (req B) wins and we accept it.
                    val swapWith = findSoloForDensitySwap(planned, left + 1)
                    if (swapWith != null) {
                        val tmp = planned[left + 1]; planned[left + 1] = planned[swapWith]; planned[swapWith] = tmp
                    }
                }
            }
            left += 2
        }
    }

    /**
     * Finds the nearest solo (1-photo) page to swap in for the dense page at [denseIdx],
     * such that the solo's own spread won't itself become dense-dense. Prefers non-people
     * solos (so we don't disturb the people cadence) and closer pages.
     */
    private fun findSoloForDensitySwap(planned: MutableList<Planned>, denseIdx: Int): Int? {
        val window = 6
        val candidates = (1 until planned.size)
            .filter { it != denseIdx && planned[it].group.photos.size == 1 && isMovable(planned, it) }
            .sortedWith(compareBy({ if (isPeoplePage(planned[it])) 1 else 0 }, { kotlin.math.abs(it - denseIdx) }))
        for (k in candidates) {
            if (kotlin.math.abs(k - denseIdx) > window) continue
            val partner = spreadPartner(k)
            if (partner == denseIdx) continue // already the same spread
            val partnerIsDense = partner != null && partner in planned.indices && isDense(planned[partner])
            if (!partnerIsDense) return k // the dense page will face a non-dense at k
        }
        return null
    }

    /**
     * A photo of people must appear within the first three pages. If it doesn't, move
     * the earliest people page up — sitting behind an establishing opener when there is
     * one, otherwise leading the book.
     */
    private fun ensurePeopleEarly(planned: MutableList<Planned>) {
        if (planned.size < 2) return
        val window = minOf(3, planned.size)
        if ((0 until window).any { isPeoplePage(planned[it]) }) return
        val idx = (window until planned.size).firstOrNull { isPeoplePage(planned[it]) } ?: return
        val target = if (isEstablishing(planned[0])) 1 else 0
        if (isMovable(planned, idx)) {
            planned.add(target, planned.removeAt(idx))
        } else {
            // The people page is inside a multi-page event — move the whole event block up
            // (req B) rather than tearing a single page out of it.
            val e = eventOf(planned[idx])
            var bs = idx; while (bs > 0 && eventOf(planned[bs - 1]) == e) bs--
            var be = idx; while (be < planned.size - 1 && eventOf(planned[be + 1]) == e) be++
            val block = ArrayList(planned.subList(bs, be + 1))
            for (k in be downTo bs) planned.removeAt(k)
            planned.addAll(minOf(target, planned.size), block)
        }
    }

    private fun isPeoplePage(p: Planned): Boolean = p.group.photos.any { photo ->
        val s = photo.signals ?: return@any false
        s.facesCount >= 1 || s.subjectType == "person" || s.subjectType == "couple" || s.subjectType == "group"
    }

    /** A same-slot-count variant of a layout, used to break identical runs. */
    private fun siblingLayout(layout: Layout, group: PhotoGroup): Layout? {
        return when (layout) {
            // FOUR_GRID and TWO_HORIZONTAL (TWO_VERTICAL is disabled) have no same-count
            // sibling to swap to.
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

    // ─── Narrative bookends (open strong / establishing, close calm / scenic) ──────

    /**
     * (#1,#4) Put the strongest establishing/arrival single at page 1. Scans a wide early window
     * and picks the highest-scoring establishing single (excluding object/misc shots), so the book
     * — and its cover, which follows page 1 — opens on a striking "here's the place" image rather
     * than merely the earliest photo. Movable pages only, and only when the current opener isn't
     * already a strong establishing single.
     */
    private fun openWithStrongestEstablishing(planned: MutableList<Planned>) {
        if (planned.size < 2 || isStrongEstablishing(planned[0])) return
        val window = minOf(OPENING_WINDOW, planned.size)
        val idx = (1 until window)
            .filter { isStrongEstablishing(planned[it]) && isMovable(planned, it) }
            .maxByOrNull { openingStrength(planned[it]) } ?: return
        planned.add(0, planned.removeAt(idx))
    }

    /**
     * (#2) Guard the opening spread: no low-narrative object/misc single, and no weak-keepsake
     * single, in the first three pages — that is where a stray souvenir/product shot is most
     * jarring. Swap any such page for the strongest non-weak single from just after it.
     */
    private fun curateOpeningPages(planned: MutableList<Planned>) {
        val protect = minOf(3, planned.size)
        for (i in 0 until protect) {
            if (!isWeakOpeningSingle(planned[i]) || !isMovable(planned, i)) continue
            val j = (protect until minOf(OPENING_WINDOW, planned.size))
                .filter { isSingle(planned[it]) && !isWeakOpeningSingle(planned[it]) && isMovable(planned, it) }
                .maxByOrNull { openingStrength(planned[it]) } ?: continue
            val tmp = planned[i]; planned[i] = planned[j]; planned[j] = tmp
        }
    }

    /**
     * (#3,#4) Close the book on a calm single. Prefer the strongest quiet/scenic single from a
     * wide tail window; if the last page is a multi-photo collage, fall back to the strongest
     * plain single so the book at least ends on a single rather than a busy grid. Movable pages
     * only, so no event is split.
     */
    private fun curateClosingPage(planned: MutableList<Planned>) {
        if (planned.size < 2) return
        val last = planned.size - 1
        if (isQuiet(planned[last])) return
        val range = maxOf(1, planned.size - CLOSING_WINDOW) until last
        val quiet = range.filter { isQuiet(planned[it]) && isMovable(planned, it) }
            .maxByOrNull { openingStrength(planned[it]) }
        val pick = quiet ?: if (!isSingle(planned[last])) {
            range.filter { isSingle(planned[it]) && isMovable(planned, it) }
                .maxByOrNull { openingStrength(planned[it]) }
        } else null
        if (pick != null) planned.add(planned.removeAt(pick))
    }

    private fun isSingle(p: Planned): Boolean = p.group.photos.size == 1

    /** Bookend strength: aesthetic + memorability of the (featured) photo, range 0–2. */
    private fun openingStrength(p: Planned): Double {
        val s = p.group.photos.firstOrNull()?.signals ?: return 0.0
        return s.aestheticScore + s.keepsakeInterest
    }

    /** A single-photo page whose subject is a low-narrative object / uncategorised misc shot. */
    private fun isObjectOrMiscSingle(p: Planned): Boolean {
        if (p.group.photos.size != 1) return false
        val s = p.group.photos[0].signals ?: return false
        return s.subjectType == "object" || s.sceneType == "misc"
    }

    private fun isStrongEstablishing(p: Planned): Boolean = isEstablishing(p) && !isObjectOrMiscSingle(p)

    /** A single unfit to lead the book: a low-narrative object/misc shot or a weak-keepsake one. */
    private fun isWeakOpeningSingle(p: Planned): Boolean {
        if (p.group.photos.size != 1) return false
        val s = p.group.photos[0].signals ?: return false
        return s.subjectType == "object" || s.sceneType == "misc" || s.keepsakeInterest < OPENING_KEEPSAKE_FLOOR
    }

    // ─── Layout choice: count + orientation + standalone + shot/scope + crop + prev ───

    private fun chooseLayout(group: PhotoGroup, prev: Layout?): Layout {
        val photos = group.photos
        return when (photos.size) {
            1 -> chooseSingle(photos[0], group)
            2 -> Layout.TWO_HORIZONTAL // two-photo pages always stack; vertical split disabled
            3 -> Layout.THREE_GRID
            else -> Layout.FOUR_GRID // four-photo pages use the 2x2 grid
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
        if (layout == Layout.FOUR_GRID && photos.size == 4) {
            return orderFourGridDiagonal(photos)
        }
        return photos
    }

    /**
     * Req 3: in a 2×2 grid, keep look-alikes apart. Cells map to
     * TL=0, TR=1, BL=2, BR=3, so {0,3} and {1,2} are the two diagonals. Place the most
     * visually similar pair (colour scheme + capture time) on the {0,3} diagonal; the
     * remaining pair falls on {1,2}. Every cell then neighbours a photo from the other
     * pair, so similar shots are never side-by-side or stacked.
     */
    private fun orderFourGridDiagonal(photos: List<Photo>): List<Photo> {
        var bestI = 0
        var bestJ = 1
        var bestSim = Double.NEGATIVE_INFINITY
        for (i in 0..3) for (j in i + 1..3) {
            val sim = pairSimilarity(photos[i], photos[j])
            if (sim > bestSim) { bestSim = sim; bestI = i; bestJ = j }
        }
        val others = (0..3).filter { it != bestI && it != bestJ }
        // TL, TR, BL, BR → similar pair on the TL/BR diagonal.
        return listOf(photos[bestI], photos[others[0]], photos[others[1]], photos[bestJ])
    }

    /**
     * Visual similarity of two photos for grid placement: dominant-colour hue proximity
     * (with a colour-temperature agreement nudge) blended with capture-time closeness.
     * Range roughly 0.0 (unrelated) … 1.0 (near-identical look, taken together).
     */
    private fun pairSimilarity(a: Photo, b: Photo): Double {
        val sa = a.signals
        val sb = b.signals
        var colorSim = 0.5
        if (sa != null && sb != null) {
            val hueA = dominantHue(sa.dominantColors)
            val hueB = dominantHue(sb.dominantColors)
            if (hueA != null && hueB != null) {
                colorSim = 1.0 - circularHueDistance(hueA, hueB) / 180.0
            }
            if (sa.colorTemperature == sb.colorTemperature) colorSim = (colorSim + 1.0) / 2.0
        }

        val ta = a.metadata.takenAt
        val tb = b.metadata.takenAt
        val timeSim = if (ta != null && tb != null) {
            val gapMinutes = kotlin.math.abs(java.time.Duration.between(ta, tb).toMinutes()).toDouble()
            kotlin.math.exp(-gapMinutes / 30.0)
        } else 0.0

        return 0.6 * colorSim + 0.4 * timeSim
    }

    /** Hue (0–360°) of a photo's most dominant colour, or null if unparseable. */
    private fun dominantHue(colors: List<String>): Double? {
        val hex = colors.firstOrNull()?.trim()?.removePrefix("#") ?: return null
        if (hex.length < 6) return null
        val r = hex.substring(0, 2).toIntOrNull(16) ?: return null
        val g = hex.substring(2, 4).toIntOrNull(16) ?: return null
        val b = hex.substring(4, 6).toIntOrNull(16) ?: return null
        return java.awt.Color.RGBtoHSB(r, g, b, null)[0] * 360.0
    }

    /** Shortest distance between two hues on the 0–360° colour wheel (0–180). */
    private fun circularHueDistance(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
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

    // Multi-image layouts get a subtle cream mat around every photo. ~2% of page height, widened
    // horizontally to the book's portrait trim (6.9×9.8) so the gutter prints an even thickness.
    private val gutterY = 0.02
    private val gutterX = 0.02 * (9.8 / 6.9)

    /**
     * Insets an edge-to-edge cell so every photo floats on a uniform cream margin: a full gutter on
     * the page's outer edges and a half-gutter on each shared interior edge (so interior gutters end
     * up the same thickness as the outer margins).
     */
    private fun mat(pos: Position, size: Size): Pair<Position, Size> {
        val eps = 1e-6
        val l = pos.x; val r = pos.x + size.width
        val t = pos.y; val b = pos.y + size.height
        val nl = l + if (l <= eps) gutterX else gutterX / 2
        val nr = r - if (r >= 1.0 - eps) gutterX else gutterX / 2
        val nt = t + if (t <= eps) gutterY else gutterY / 2
        val nb = b - if (b >= 1.0 - eps) gutterY else gutterY / 2
        return Pair(Position(nl, nt), Size(nr - nl, nb - nt))
    }

    /** Slot rectangle (normalised 0–1 position + size) for slot [index] of a [layout]. Pure — also
     *  reused when the user switches a page's layout on the preview. Single-image layouts stay
     *  full-bleed; multi-image layouts are matted (see [mat]). */
    fun getSlotGeometry(layout: Layout, index: Int, totalSlots: Int): Pair<Position, Size> {
        return when (layout) {
            Layout.SINGLE_FULL, Layout.HERO_LANDSCAPE, Layout.DOUBLE_PAGE_FULL_BLEED ->
                Pair(Position(0.0, 0.0), Size(1.0, 1.0))

            Layout.SINGLE_FRAMED ->
                Pair(Position(0.10, 0.08), Size(0.80, 0.84)) // generous white margin

            Layout.TWO_HORIZONTAL -> mat(Position(0.0, index * 0.5), Size(1.0, 0.5))

            Layout.TWO_VERTICAL -> mat(Position(index * 0.5, 0.0), Size(0.5, 1.0))

            Layout.THREE_GRID -> when (index) {
                0 -> mat(Position(0.0, 0.0), Size(1.0, 0.5))
                1 -> mat(Position(0.0, 0.5), Size(0.5, 0.5))
                else -> mat(Position(0.5, 0.5), Size(0.5, 0.5))
            }

            Layout.FOUR_GRID -> {
                val x = (index % 2) * 0.5
                val y = (index / 2) * 0.5
                mat(Position(x.toDouble(), y.toDouble()), Size(0.5, 0.5))
            }
        }
    }
}
