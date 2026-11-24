package com.atlaso.controller.dto

data class ValidateCouponRequest(
    val code: String,
    val quantity: Int? = 1,
)

/** Public preview — deliberately omits the Razorpay offer id. [free] = 100% off, skips payment. */
data class ValidateCouponResponse(
    val valid: Boolean,
    val code: String,
    val discountMinor: Long,
    val finalMinor: Long,
    val free: Boolean = false,
    /** Lowercase partner co-brand for B2B coupons — the back cover reads "atlaso x <partnerName>". */
    val partnerName: String? = null,
    val message: String? = null,
)

/** Admin: create/upsert a coupon. Maps a code to a Razorpay offer, or a full-discount (free) coupon. */
data class CreateCouponRequest(
    val code: String,
    /** Required unless [fullDiscount] is true (a full-discount coupon has no Razorpay offer). */
    val razorpayOfferId: String? = null,
    /** 100%-off coupon that skips the Razorpay flow entirely. */
    val fullDiscount: Boolean = false,
    /** Partner (B2B) coupon — requires [partnerName]. Independent of [fullDiscount]. */
    val partner: Boolean = false,
    /** Co-brand for the back cover; lowercased, max 16 chars. Required when [partner]. */
    val partnerName: String? = null,
    val description: String? = null,
    val discountType: String? = null,   // PERCENT | FLAT
    val discountValue: Long? = null,
    val maxDiscountMinor: Long? = null,
    val minAmountMinor: Long? = null,
    val forceOffer: Boolean = false,
    val active: Boolean = true,
    val validFrom: String? = null,      // ISO-8601 instant
    val validUntil: String? = null,     // ISO-8601 instant
    val maxUses: Int? = null,
)

data class AdminCouponDto(
    val id: String,
    val code: String,
    val razorpayOfferId: String?,
    val fullDiscount: Boolean,
    val partner: Boolean,
    val partnerName: String?,
    val description: String?,
    val discountType: String?,
    val discountValue: Long?,
    val maxDiscountMinor: Long?,
    val minAmountMinor: Long?,
    val forceOffer: Boolean,
    val active: Boolean,
    val validFrom: String?,
    val validUntil: String?,
    val maxUses: Int?,
    val usedCount: Int,
)
