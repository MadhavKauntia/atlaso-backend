package com.atlaso.domain.coupon

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

/**
 * A coupon code that maps to a Razorpay Offer. The real discount is applied by
 * Razorpay at payment time (the offer is linked to the order via the `offers`
 * array); the `discount*` fields here are used only to preview the saving in the
 * checkout UI.
 */
@Entity
@Table(name = "coupons")
data class Coupon(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    /** Stored/compared case-insensitively (unique on UPPER(code)). */
    @Column(nullable = false, length = 64)
    val code: String,

    /** The linked Razorpay offer that applies the discount at payment time. Null for full-discount
     *  coupons, which skip Razorpay entirely (see [fullDiscount]). */
    @Column(name = "razorpay_offer_id", length = 64)
    val razorpayOfferId: String? = null,

    /** 100%-off coupon that bypasses payment: create-order records the order directly, no Razorpay. */
    @Column(name = "full_discount", nullable = false)
    val fullDiscount: Boolean = false,

    @Column(length = 255)
    val description: String? = null,

    /** PERCENT or FLAT — preview only. */
    @Column(name = "discount_type", length = 16)
    val discountType: String? = null,

    /** Percent (e.g. 20) when PERCENT, or flat paise when FLAT — preview only. */
    @Column(name = "discount_value")
    val discountValue: Long? = null,

    @Column(name = "max_discount_minor")
    val maxDiscountMinor: Long? = null,

    @Column(name = "min_amount_minor")
    val minAmountMinor: Long? = null,

    @Column(name = "force_offer", nullable = false)
    val forceOffer: Boolean = false,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "valid_from")
    val validFrom: Instant? = null,

    @Column(name = "valid_until")
    val validUntil: Instant? = null,

    @Column(name = "max_uses")
    val maxUses: Int? = null,

    @Column(name = "used_count", nullable = false)
    val usedCount: Int = 0,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant? = null,
)
