package com.atlaso.domain.book

enum class Layout {
    SINGLE_FULL,        // full-bleed single (portrait/square subject)
    HERO_LANDSCAPE,     // full-bleed single (landscape subject)
    SINGLE_FRAMED,      // single with generous white margin (premium / quiet)
    TWO_HORIZONTAL,     // two stacked (each full width, half height)
    TWO_VERTICAL,       // two side-by-side (each half width, full height)
    THREE_GRID,         // one featured top + two below (THREE_FEATURE)
    FOUR_GRID,          // 2x2
    DOUBLE_PAGE_FULL_BLEED // one landscape across two facing pages
}

/** How many photo slots a layout renders. Drives layout-switching (grow/shrink) on the preview. */
val Layout.slotCount: Int
    get() = when (this) {
        Layout.SINGLE_FULL, Layout.HERO_LANDSCAPE, Layout.SINGLE_FRAMED, Layout.DOUBLE_PAGE_FULL_BLEED -> 1
        Layout.TWO_HORIZONTAL, Layout.TWO_VERTICAL -> 2
        Layout.THREE_GRID -> 3
        Layout.FOUR_GRID -> 4
    }
