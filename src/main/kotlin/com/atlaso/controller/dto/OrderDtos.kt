package com.atlaso.controller.dto

import com.atlaso.domain.order.Order

/** Order + shipping summary for the confirmation page. */
data class OrderSummaryResponse(
    val orderNumber: String,
    val customerName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val state: String?,
    val pincode: String?,
    val country: String?,
    val phone: String?,
) {
    companion object {
        fun from(order: Order) = OrderSummaryResponse(
            orderNumber = "ATL-${order.number}",
            customerName = order.customerName,
            addressLine1 = order.addressLine1,
            addressLine2 = order.addressLine2,
            city = order.city,
            state = order.state,
            pincode = order.pincode,
            country = order.shipCountry,
            phone = order.phone,
        )
    }
}
