package com.atlaso.service

import com.atlaso.domain.coupon.Coupon
import com.atlaso.repository.CouponRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import java.time.Instant

class CouponServiceTest {

    private val repo = mock(CouponRepository::class.java)
    private val service = CouponService(repo)

    private fun onFindReturn(c: Coupon?) { `when`(repo.findByCode(anyString())).thenReturn(c) }

    private val amount = 199900L // one book

    private fun coupon(
        code: String = "SAVE20",
        type: String? = "PERCENT",
        value: Long? = 20,
        maxDiscount: Long? = null,
        minAmount: Long? = null,
        active: Boolean = true,
        validFrom: Instant? = null,
        validUntil: Instant? = null,
        maxUses: Int? = null,
        usedCount: Int = 0,
        fullDiscount: Boolean = false,
    ) = Coupon(
        code = code,
        razorpayOfferId = if (fullDiscount) null else "offer_TEST",
        fullDiscount = fullDiscount,
        discountType = type,
        discountValue = value,
        maxDiscountMinor = maxDiscount,
        minAmountMinor = minAmount,
        active = active,
        validFrom = validFrom,
        validUntil = validUntil,
        maxUses = maxUses,
        usedCount = usedCount,
    )

    @Test
    fun `valid percent coupon previews the saving`() {
        onFindReturn(coupon())
        val result = service.validate("save20", amount)
        assertTrue(result.valid)
        assertEquals("SAVE20", result.code)
        assertEquals(39980L, result.discountMinor) // 20% of 199900
        assertEquals(159920L, result.finalMinor)
    }

    @Test
    fun `flat coupon subtracts a fixed amount`() {
        onFindReturn(coupon(type = "FLAT", value = 50000))
        val result = service.validate("SAVE20", amount)
        assertTrue(result.valid)
        assertEquals(50000L, result.discountMinor)
        assertEquals(149900L, result.finalMinor)
    }

    @Test
    fun `percent discount is capped by maxDiscountMinor`() {
        onFindReturn(coupon(maxDiscount = 10000))
        val result = service.validate("SAVE20", amount)
        assertEquals(10000L, result.discountMinor)
    }

    @Test
    fun `unknown code is invalid`() {
        onFindReturn(null)
        val result = service.validate("NOPE", amount)
        assertFalse(result.valid)
        assertNull(result.offerId)
        assertEquals(amount, result.finalMinor)
    }

    @Test
    fun `inactive coupon is invalid`() {
        onFindReturn(coupon(active = false))
        assertFalse(service.validate("SAVE20", amount).valid)
    }

    @Test
    fun `expired coupon is invalid`() {
        onFindReturn(coupon(validUntil = Instant.now().minusSeconds(60)))
        assertFalse(service.validate("SAVE20", amount).valid)
    }

    @Test
    fun `not-yet-active coupon is invalid`() {
        onFindReturn(coupon(validFrom = Instant.now().plusSeconds(3600)))
        assertFalse(service.validate("SAVE20", amount).valid)
    }

    @Test
    fun `fully redeemed coupon is invalid`() {
        onFindReturn(coupon(maxUses = 5, usedCount = 5))
        assertFalse(service.validate("SAVE20", amount).valid)
    }

    @Test
    fun `below minimum amount is invalid`() {
        onFindReturn(coupon(minAmount = 500000))
        assertFalse(service.validate("SAVE20", amount).valid)
    }

    @Test
    fun `full-discount coupon is free — 100% off, skips payment`() {
        onFindReturn(coupon(code = "FREEBOOK", type = null, value = null, fullDiscount = true))
        val result = service.validate("freebook", amount)
        assertTrue(result.valid)
        assertTrue(result.free)
        assertEquals(amount, result.discountMinor) // whole order waived
        assertEquals(0L, result.finalMinor)
    }

    @Test
    fun `resolveForOrder throws on unknown code`() {
        onFindReturn(null)
        assertThrows(CouponInvalidException::class.java) { service.resolveForOrder("NOPE", amount) }
    }

    @Test
    fun `resolveForOrder returns the coupon when eligible`() {
        val c = coupon()
        onFindReturn(c)
        assertEquals(c, service.resolveForOrder("SAVE20", amount))
    }
}
