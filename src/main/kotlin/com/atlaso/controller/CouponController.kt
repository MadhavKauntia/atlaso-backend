package com.atlaso.controller

import com.atlaso.controller.dto.ValidateCouponRequest
import com.atlaso.controller.dto.ValidateCouponResponse
import com.atlaso.service.CouponService
import com.atlaso.service.Pricing
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/coupons")
class CouponController(
    private val couponService: CouponService,
) {
    /** Validates a coupon and previews the saving for the checkout page. */
    @PostMapping("/validate")
    fun validate(@RequestBody request: ValidateCouponRequest): ResponseEntity<ValidateCouponResponse> {
        val qty = (request.quantity ?: 1).coerceAtLeast(1)
        val amountMinor = qty * Pricing.UNIT_PRICE_MINOR
        val result = couponService.validate(request.code, amountMinor)
        return ResponseEntity.ok(
            ValidateCouponResponse(
                valid = result.valid,
                code = result.code,
                discountMinor = result.discountMinor,
                finalMinor = result.finalMinor,
                free = result.free,
                message = result.message,
            )
        )
    }
}
