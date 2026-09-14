package com.atlaso.domain.photo

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A server-recorded upload initiation. Confirm looks this up by (trip, photoId) and trusts
 * its stored key/thumbnail/content-type — the client can only confirm a key we actually
 * handed out, exactly once. The presigned PUT already binds Content-Length, so the object's
 * size matches [maxSizeBytes].
 */
@Entity
@Table(name = "upload_grants")
data class UploadGrant(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "trip_id", nullable = false)
    val tripId: UUID,

    @Column(name = "photo_id", nullable = false)
    val photoId: UUID,

    @Column(name = "storage_key", nullable = false, length = 512)
    val storageKey: String,

    @Column(name = "thumbnail_key", length = 512)
    val thumbnailKey: String? = null,

    @Column(name = "content_type", nullable = false, length = 64)
    val contentType: String,

    @Column(name = "max_size_bytes", nullable = false)
    val maxSizeBytes: Long,

    @Column(nullable = false)
    var consumed: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
