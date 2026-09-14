package com.atlaso.controller

import com.atlaso.controller.dto.AdminCouponDto
import com.atlaso.controller.dto.CreateCouponRequest
import com.atlaso.domain.coupon.Coupon
import com.atlaso.service.CouponService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Admin coupon management. Guarded by [com.atlaso.config.AdminKeyInterceptor]
 * (X-Admin-Key header) via the admin path matcher.
 */
@RestController
@RequestMapping("/api/admin/coupons")
class AdminCouponController(
    private val couponService: CouponService,
) {
    @GetMapping
    fun list(): ResponseEntity<List<AdminCouponDto>> =
        ResponseEntity.ok(couponService.listAll().map { it.toDto() })

    @PostMapping
    fun create(@RequestBody req: CreateCouponRequest): ResponseEntity<AdminCouponDto> {
        val coupon = Coupon(
            code = req.code.trim().uppercase(),
            razorpayOfferId = req.razorpayOfferId.trim(),
            description = req.description,
            discountType = req.discountType?.uppercase(),
            discountValue = req.discountValue,
            maxDiscountMinor = req.maxDiscountMinor,
            minAmountMinor = req.minAmountMinor,
            forceOffer = req.forceOffer,
            active = req.active,
            validFrom = req.validFrom?.let { Instant.parse(it) },
            validUntil = req.validUntil?.let { Instant.parse(it) },
            maxUses = req.maxUses,
        )
        return ResponseEntity.ok(couponService.save(coupon).toDto())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        couponService.delete(id)
        return ResponseEntity.noContent().build()
    }

    private fun Coupon.toDto() = AdminCouponDto(
        id = id.toString(),
        code = code,
        razorpayOfferId = razorpayOfferId,
        description = description,
        discountType = discountType,
        discountValue = discountValue,
        maxDiscountMinor = maxDiscountMinor,
        minAmountMinor = minAmountMinor,
        forceOffer = forceOffer,
        active = active,
        validFrom = validFrom?.toString(),
        validUntil = validUntil?.toString(),
        maxUses = maxUses,
        usedCount = usedCount,
    )
}
