package com.atlaso.domain.trip

enum class TripStatus {
    CREATED,
    UPLOADING_PHOTOS,
    ANALYZING_PHOTOS,
    READY_FOR_BOOK_GENERATION,
    BOOK_GENERATED,
    ORDERED
}
