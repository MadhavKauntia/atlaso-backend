package com.atlaso.domain.user

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

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

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    val updatedAt: Instant? = null
)
