package com.atlaso.controller.dto

import com.atlaso.domain.order.Order
import java.time.Instant
import java.util.UUID

data class ShipRequest(val trackingNumber: String? = null)

data class AdminOrderDto(
    val id: UUID,
    val number: Long,
    val status: String,
    val createdAt: Instant?,
    val shippedAt: Instant?,
    val trackingNumber: String?,
    val bookTitle: String?,
    val bookId: UUID?,
    val tripId: UUID,
    val quantity: Int,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String?,
    val razorpayPaymentId: String?,
    val razorpayOrderId: String?,
    val customerName: String?,
    val customerEmail: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val state: String?,
    val pincode: String?,
    val country: String?,
    val phone: String?,
) {
    companion object {
        fun from(o: Order) = AdminOrderDto(
            id = o.id!!,
            number = o.number,
            status = o.status,
            createdAt = o.createdAt,
            shippedAt = o.shippedAt,
            trackingNumber = o.trackingNumber,
            bookTitle = o.bookTitle,
            bookId = o.bookId,
            tripId = o.trip.id!!,
            quantity = o.quantity,
            amountMinor = o.amountMinor,
            currency = o.currency,
            paymentMethod = o.paymentMethod,
            razorpayPaymentId = o.razorpayPaymentId,
            razorpayOrderId = o.razorpayOrderId,
            customerName = o.customerName,
            customerEmail = o.customerEmail,
            addressLine1 = o.addressLine1,
            addressLine2 = o.addressLine2,
            city = o.city,
            state = o.state,
            pincode = o.pincode,
            country = o.shipCountry,
            phone = o.phone,
        )
    }
}
