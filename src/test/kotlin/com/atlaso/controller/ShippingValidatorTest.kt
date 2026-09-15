package com.atlaso.controller

import com.atlaso.controller.dto.CreateOrderRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ShippingValidatorTest {

    private fun req(
        addressLine1: String? = "12 MG Road",
        addressLine2: String? = "Flat 4",
        city: String? = "Bengaluru",
        state: String? = "Karnataka",
        pincode: String? = "560001",
        country: String? = "India",
        phone: String? = "+919876543210",
    ) = CreateOrderRequest(
        tripId = UUID.randomUUID(), addressLine1 = addressLine1, addressLine2 = addressLine2,
        city = city, state = state, pincode = pincode, country = country, phone = phone,
    )

    @Test
    fun `accepts and normalizes a complete address`() {
        val v = ShippingValidator.validate(req(phone = "9876543210")).getOrThrow()
        assertEquals("12 MG Road", v.addressLine1)
        assertEquals("Bengaluru", v.city)
        assertEquals("560001", v.pincode)
        assertEquals("+919876543210", v.phone) // canonicalised to +91
    }

    @Test
    fun `normalizes +91 and leading-zero phone forms`() {
        assertEquals("+919876543210", ShippingValidator.validate(req(phone = "+91 98765 43210")).getOrThrow().phone)
        assertEquals("+919876543210", ShippingValidator.validate(req(phone = "09876543210")).getOrThrow().phone)
    }

    @Test
    fun `trims a blank optional address line 2 to null`() {
        assertNull(ShippingValidator.validate(req(addressLine2 = "   ")).getOrThrow().addressLine2)
    }

    @Test
    fun `rejects a missing address line 1`() {
        assertTrue(ShippingValidator.validate(req(addressLine1 = "  ")).isFailure)
        assertTrue(ShippingValidator.validate(req(addressLine1 = null)).isFailure)
    }

    @Test
    fun `rejects missing city and state`() {
        assertTrue(ShippingValidator.validate(req(city = null)).isFailure)
        assertTrue(ShippingValidator.validate(req(state = "")).isFailure)
    }

    @Test
    fun `rejects a bad pincode`() {
        assertTrue(ShippingValidator.validate(req(pincode = "1234")).isFailure)
        assertTrue(ShippingValidator.validate(req(pincode = "abcdef")).isFailure)
        assertTrue(ShippingValidator.validate(req(pincode = null)).isFailure)
    }

    @Test
    fun `rejects a missing country`() {
        assertTrue(ShippingValidator.validate(req(country = null)).isFailure)
    }

    @Test
    fun `rejects an invalid mobile number`() {
        assertTrue(ShippingValidator.validate(req(phone = "12345")).isFailure)      // too short
        assertTrue(ShippingValidator.validate(req(phone = "1234567890")).isFailure) // not 6-9 leading
        assertTrue(ShippingValidator.validate(req(phone = null)).isFailure)
    }

    @Test
    fun `rejects oversized values`() {
        assertTrue(ShippingValidator.validate(req(city = "x".repeat(129))).isFailure)
        assertTrue(ShippingValidator.validate(req(country = "x".repeat(65))).isFailure)
        assertTrue(ShippingValidator.validate(req(addressLine1 = "x".repeat(256))).isFailure)
    }
}
