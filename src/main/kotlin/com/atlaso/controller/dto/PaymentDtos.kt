package com.atlaso.controller.dto

import java.util.UUID

data class CreateOrderRequest(
    val amount: Long,
    val currency: String? = null,
    val receipt: String? = null,
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
)
