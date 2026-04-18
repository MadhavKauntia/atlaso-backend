package com.atlaso.domain.book

enum class BookStatus {
    GENERATING,
    READY_FOR_PREVIEW,
    EXPORTING_PDF,
    PDF_READY,
    FAILED
}
