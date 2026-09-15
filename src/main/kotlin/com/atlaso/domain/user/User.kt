package com.atlaso.domain.user

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

/** Free book previews a user gets between paid orders (see [User.freePreviewsRemaining]). */
const val FREE_PREVIEW_QUOTA = 3

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, unique = true, name = "google_sub", length = 255)
    val googleSub: String,

    @Column(nullable = false, length = 255)
    val email: String,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column(name = "picture_url", length = 1024)
    var pictureUrl: String? = null,

    // Free book previews remaining before this user must place an order to generate more.
    // Consumed on the first generation of a new trip; reset to FREE_PREVIEW_QUOTA on a paid order.
    @Column(nullable = false, name = "free_previews_remaining")
    var freePreviewsRemaining: Int = FREE_PREVIEW_QUOTA,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    val updatedAt: Instant? = null
)
