package com.atlaso.controller

import com.atlaso.controller.dto.CreateOrderRequest

/** Normalized, server-validated shipping details ready to persist on a Checkout. */
data class ValidatedShipping(
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val state: String,
    val pincode: String,
    val country: String,
    val phone: String,
)

/**
 * Server-side shipping validation for the India-only checkout. The webhook records an order
 * straight from the Checkout without the browser, so the address must be complete and
 * well-formed *before* the Razorpay order is ever created — the client's own validation is not
 * trusted. Field lengths are also bounded to match the orders/checkouts columns so a recorded
 * order can never fail on an oversized value.
 */
object ShippingValidator {
    private val PINCODE = Regex("^\\d{6}$")
    private val MOBILE = Regex("^[6-9]\\d{9}$")

    fun validate(request: CreateOrderRequest): Result<ValidatedShipping> {
        val addressLine1 = request.addressLine1?.trim().orEmpty()
        if (addressLine1.isEmpty()) return fail("Address line 1 is required")
        if (addressLine1.length > 255) return fail("Address line 1 is too long")

        val addressLine2 = request.addressLine2?.trim()?.takeIf { it.isNotEmpty() }
        if (addressLine2 != null && addressLine2.length > 255) return fail("Address line 2 is too long")

        val city = request.city?.trim().orEmpty()
        if (city.isEmpty()) return fail("City is required")
        if (city.length > 128) return fail("City is too long")

        val state = request.state?.trim().orEmpty()
        if (state.isEmpty()) return fail("State is required")
        if (state.length > 128) return fail("State is too long")

        val pincode = request.pincode?.trim().orEmpty()
        if (!PINCODE.matches(pincode)) return fail("Enter a valid 6-digit pincode")

        val country = request.country?.trim().orEmpty()
        if (country.isEmpty()) return fail("Country is required")
        if (country.length > 64) return fail("Country is too long")

        // Accept the number with or without a +91 / 0 prefix; validate the 10-digit national part
        // and store it in a single canonical +91XXXXXXXXXX form.
        val digits = request.phone?.filter { it.isDigit() }.orEmpty()
        val national = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            else -> return fail("Enter a valid 10-digit mobile number")
        }
        if (!MOBILE.matches(national)) return fail("Enter a valid 10-digit mobile number")

        return Result.success(
            ValidatedShipping(
                addressLine1 = addressLine1,
                addressLine2 = addressLine2,
                city = city,
                state = state,
                pincode = pincode,
                country = country,
                phone = "+91$national",
            )
        )
    }

    private fun fail(message: String): Result<ValidatedShipping> =
        Result.failure(IllegalArgumentException(message))
}
