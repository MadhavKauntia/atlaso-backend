package com.atlaso.repository

/**
 * Just the S3 keys of a photo or upload grant. Used when purging a trip so key collection
 * doesn't load managed entities that still reference the trip being deleted (which made
 * Hibernate throw TransientObjectException on the next flush).
 */
interface StorageKeys {
    val storageKey: String
    val thumbnailKey: String?
}
