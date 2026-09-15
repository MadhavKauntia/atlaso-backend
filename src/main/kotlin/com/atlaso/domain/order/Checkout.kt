package com.atlaso.domain.order

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Server-side binding created when a Razorpay order is created. It records the trip, user,
 * quantity, and the amount we expect to be captured — none of which the client can tamper
 * with at verify time. Payment verification looks this up by razorpay_order_id and trusts
 * these values instead of anything the client re-sends.
 */
@Entity
@Table(name = "checkouts")
data class Checkout(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "razorpay_order_id", nullable = false, unique = true, length = 64)
    val razorpayOrderId: String,

    @Column(name = "trip_id", nullable = false)
    val tripId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    val quantity: Int,

    /** Amount (paise) we expect Razorpay to capture (list price minus any coupon offer). */
    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,

    @Column(nullable = false, length = 8)
    val currency: String = "INR",

    @Column(name = "coupon_code", length = 64)
    val couponCode: String? = null,

    // Shipping details captured at create-order time (recipient name/email come from the
    // account). Persisted here so the webhook can record a shippable order without the browser.
    @Column(name = "address_line1", length = 255)
    val addressLine1: String? = null,

    @Column(name = "address_line2", length = 255)
    val addressLine2: String? = null,

    @Column(length = 120)
    val city: String? = null,

    @Column(length = 120)
    val state: String? = null,

    @Column(length = 16)
    val pincode: String? = null,

    @Column(name = "ship_country", length = 80)
    val shipCountry: String? = null,

    @Column(length = 32)
    val phone: String? = null,

    @Column(nullable = false, length = 16)
    var status: String = "PENDING", // PENDING | COMPLETED

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
