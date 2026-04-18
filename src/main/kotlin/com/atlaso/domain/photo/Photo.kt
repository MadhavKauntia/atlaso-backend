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

    @Column(nullable = false, name = "original_filename")
    val originalFilename: String,

    @Column(nullable = false, name = "content_type", length = 100)
    val contentType: String,

    @Column(nullable = false, name = "file_size")
    val fileSize: Long,

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
