package com.atlaso.domain.trip

import com.atlaso.domain.user.User
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "trips")
data class Trip(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column(length = 255)
    var destination: String? = null,

    @Column(name = "start_date")
    var startDate: LocalDate? = null,

    @Column(name = "end_date")
    var endDate: LocalDate? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id", nullable = true)
    var user: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    var status: TripStatus = TripStatus.CREATED,

    // SHA-256 hash of the guest capability token handed out at creation. Required to operate
    // on a guest trip by UUID (upload/claim) so the UUID alone isn't a password. Null for
    // legacy trips created before this was introduced (those keep the old behaviour).
    @Column(name = "guest_token_hash", length = 64)
    var guestTokenHash: String? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: Instant? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    val updatedAt: Instant? = null
)
