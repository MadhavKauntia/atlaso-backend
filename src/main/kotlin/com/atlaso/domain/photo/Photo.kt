package com.atlaso.domain.photo

import com.atlaso.domain.trip.Trip
import io.hypersistence.utils.hibernate.type.json.JsonType
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.Type
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "photos")
data class Photo(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    val trip: Trip,

    @Column(nullable = false, unique = true, name = "storage_key")
    val storageKey: String,

    // Optional small (~360px) display derivative uploaded alongside the full
    // image, so thumbnail-heavy views (preview rail, picker) load ~25KB instead
    // of the full-res original. Null for photos uploaded before this existed.
    @Column(name = "thumbnail_key", length = 512)
    var thumbnailKey: String? = null,

    @Column(nullable = false, name = "original_filename")
    val originalFilename: String,

    @Column(nullable = false, name = "content_type", length = 100)
    val contentType: String,

    @Column(nullable = false, name = "file_size")
    val fileSize: Long,

    /** Stored thumbnail size (bytes), so confirmed thumbnails count toward the trip byte quota. */
    @Column(name = "thumbnail_size_bytes")
    val thumbnailSizeBytes: Long? = null,

    @Type(JsonType::class)
    @Column(nullable = false, columnDefinition = "jsonb")
    val metadata: PhotoMetadata,

    @Type(JsonType::class)
    @Column(columnDefinition = "jsonb")
    var signals: PhotoSignals? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "uploaded_at")
    val uploadedAt: Instant? = null,

    @Column(name = "analyzed_at")
    var analyzedAt: Instant? = null,

    @Column(nullable = false)
    var rotation: Int = 0
)
