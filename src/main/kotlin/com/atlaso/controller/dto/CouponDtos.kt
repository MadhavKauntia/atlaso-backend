package com.atlaso.controller.dto

data class ValidateCouponRequest(
    val code: String,
    val quantity: Int? = 1,
)

/** Public preview — deliberately omits the Razorpay offer id. */
data class ValidateCouponResponse(
    val valid: Boolean,
    val code: String,
    val discountMinor: Long,
    val finalMinor: Long,
    val message: String? = null,
)

/** Admin: create/upsert a coupon that maps a code to a Razorpay offer. */
data class CreateCouponRequest(
    val code: String,
    val razorpayOfferId: String,
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
    val razorpayOfferId: String,
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
