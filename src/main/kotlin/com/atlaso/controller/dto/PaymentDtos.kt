package com.atlaso.controller.dto

import java.util.UUID

data class CreateOrderRequest(
    // Price is computed server-side from tripId + quantity (+ coupon). Any client-supplied
    // `amount` is ignored — kept nullable only so older clients still deserialize.
    val tripId: UUID,
    val quantity: Int? = null,
    val amount: Long? = null,
    val currency: String? = null,
    val receipt: String? = null,
    // When present, the coupon's Razorpay offer is linked to the order.
    val couponCode: String? = null,
)

data class CreateOrderResponse(
    val orderId: String,
    val amount: Long,
    val currency: String,
)

data class VerifyPaymentRequest(
    val razorpayOrderId: String? = null,
    val razorpayPaymentId: String? = null,
    val razorpaySignature: String? = null,
    // Optional — when present, the trip is marked ORDERED after a valid signature.
    val tripId: UUID? = null,
    val quantity: Int? = null,
    val couponCode: String? = null,
    // Shipping details captured at checkout (name/email come from the account).
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val country: String? = null,
    val phone: String? = null,
)
