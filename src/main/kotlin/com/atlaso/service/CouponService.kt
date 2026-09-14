package com.atlaso.service

import com.atlaso.domain.coupon.Coupon
import com.atlaso.repository.CouponRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/** Outcome of validating a coupon against an order amount. */
data class CouponValidation(
    val valid: Boolean,
    val code: String,
    val offerId: String?,       // null when invalid — never leaked to the client
    val forceOffer: Boolean,
    val discountMinor: Long,    // previewed saving in paise (0 when invalid)
    val finalMinor: Long,       // amountMinor - discountMinor (preview only)
    val message: String?,       // reason when invalid
)

/** Thrown when a coupon cannot be applied to an order (create-order path). */
class CouponInvalidException(message: String) : RuntimeException(message)

@Service
class CouponService(
    private val couponRepository: CouponRepository,
) {
    private val logger = LoggerFactory.getLogger(CouponService::class.java)

    /**
     * Validates [code] against [amountMinor] and previews the saving. Never throws —
     * returns an invalid [CouponValidation] with a reason so the UI can show it.
     */
    fun validate(code: String, amountMinor: Long): CouponValidation {
        val trimmed = code.trim()
        val invalid = { reason: String ->
            CouponValidation(false, trimmed.uppercase(), null, false, 0, amountMinor, reason)
        }
        if (trimmed.isBlank()) return invalid("Enter a coupon code")

        val coupon = couponRepository.findByCode(trimmed) ?: return invalid("Invalid coupon code")
        checkEligibility(coupon, amountMinor)?.let { return invalid(it) }

        val discount = previewDiscount(coupon, amountMinor)
        return CouponValidation(
            valid = true,
            code = coupon.code.uppercase(),
            offerId = coupon.razorpayOfferId,
            forceOffer = coupon.forceOffer,
            discountMinor = discount,
            finalMinor = (amountMinor - discount).coerceAtLeast(0),
            message = null,
        )
    }

    /**
     * Re-validates [code] for the create-order path and returns the coupon entity.
     * Throws [CouponInvalidException] if it cannot be applied — never trust a
     * client-supplied discount.
     */
    fun resolveForOrder(code: String, amountMinor: Long): Coupon {
        val coupon = couponRepository.findByCode(code.trim())
            ?: throw CouponInvalidException("Invalid coupon code")
        checkEligibility(coupon, amountMinor)?.let { throw CouponInvalidException(it) }
        return coupon
    }

    /** The stored coupon for [code], or null. Used to record it on the paid order. */
    fun findByCode(code: String): Coupon? = couponRepository.findByCode(code.trim())

    /** Admin: all coupons, newest first. */
    fun listAll(): List<Coupon> = couponRepository.findAllByOrderByCreatedAtDesc()

    /**
     * Admin: create or update a coupon (upsert on code). The Razorpay offer must
     * already exist on the Dashboard — [Coupon.razorpayOfferId] is what links it.
     */
    fun save(coupon: Coupon): Coupon {
        val existing = couponRepository.findByCode(coupon.code)
        val toSave = if (existing?.id != null) coupon.copy(id = existing.id, usedCount = existing.usedCount) else coupon
        return couponRepository.save(toSave)
    }

    /** Admin: delete a coupon by id. */
    fun delete(id: java.util.UUID) = couponRepository.deleteById(id)

    /** Bumps the redemption counter after a real paid order — best-effort. */
    fun recordRedemption(coupon: Coupon) {
        coupon.id?.let { couponRepository.incrementUsage(it) }
    }

    /** Returns null when eligible, otherwise a human-readable reason. */
    private fun checkEligibility(coupon: Coupon, amountMinor: Long): String? {
        if (!coupon.active) return "This coupon is no longer active"
        val now = Instant.now()
        if (coupon.validFrom != null && now.isBefore(coupon.validFrom)) return "This coupon isn't active yet"
        if (coupon.validUntil != null && now.isAfter(coupon.validUntil)) return "This coupon has expired"
        if (coupon.maxUses != null && coupon.usedCount >= coupon.maxUses) return "This coupon has been fully redeemed"
        if (coupon.minAmountMinor != null && amountMinor < coupon.minAmountMinor) {
            return "Order total is below this coupon's minimum"
        }
        return null
    }

    private fun previewDiscount(coupon: Coupon, amountMinor: Long): Long {
        val value = coupon.discountValue ?: return 0
        val raw = when (coupon.discountType?.uppercase()) {
            "PERCENT" -> amountMinor * value / 100
            "FLAT" -> value
            else -> 0
        }
        val capped = coupon.maxDiscountMinor?.let { minOf(raw, it) } ?: raw
        return capped.coerceIn(0, amountMinor)
    }
}
