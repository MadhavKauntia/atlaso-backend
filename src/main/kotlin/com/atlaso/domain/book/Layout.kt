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
