package com.atlaso.domain.order

import com.atlaso.domain.trip.Trip
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * A paid order. Created when a Razorpay payment is verified; backs the
 * downloadable payment receipt.
 */
@Entity
@Table(name = "orders")
data class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Sequential human-facing number; rendered as ATL-{number} / ATL-R-{number}. */
    @Column(nullable = false)
    val number: Long,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    val trip: Trip,

    @Column(name = "book_id")
    val bookId: UUID? = null,

    @Column(name = "book_title", length = 255)
    val bookTitle: String? = null,

    @Column(name = "razorpay_order_id", length = 64)
    val razorpayOrderId: String? = null,

    @Column(name = "razorpay_payment_id", length = 64)
    val razorpayPaymentId: String? = null,

    @Column(name = "payment_method", length = 32)
    val paymentMethod: String? = null,

    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,

    @Column(nullable = false, length = 8)
    val currency: String = "INR",

    @Column(nullable = false)
    val quantity: Int = 1,

    @Column(name = "customer_name", length = 255)
    val customerName: String? = null,

    @Column(name = "customer_email", length = 255)
    val customerEmail: String? = null,

    @Column(name = "address_line1", length = 255)
    val addressLine1: String? = null,

    @Column(name = "address_line2", length = 255)
    val addressLine2: String? = null,

    @Column(length = 128)
    val city: String? = null,

    @Column(length = 128)
    val state: String? = null,

    @Column(length = 32)
    val pincode: String? = null,

    @Column(name = "ship_country", length = 64)
    val shipCountry: String? = null,

    @Column(length = 32)
    val phone: String? = null,

    @Column(nullable = false, length = 32)
    var status: String = "PAID",

    @Column(name = "tracking_number", length = 128)
    var trackingNumber: String? = null,

    @Column(name = "shipped_at")
    var shippedAt: Instant? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,
)
