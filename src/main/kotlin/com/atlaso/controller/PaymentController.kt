package com.atlaso.controller

import com.atlaso.controller.dto.CreateOrderRequest
import com.atlaso.controller.dto.CreateOrderResponse
import com.atlaso.controller.dto.VerifyPaymentRequest
import com.atlaso.domain.order.Checkout
import com.atlaso.repository.CheckoutRepository
import com.atlaso.service.CouponService
import com.atlaso.service.OrderService
import com.atlaso.service.PaymentVerificationException
import com.atlaso.service.Pricing
import com.atlaso.service.PaymentService
import com.atlaso.service.RazorpayAuthException
import com.atlaso.service.RazorpayException
import com.atlaso.service.TripService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService,
    private val tripService: TripService,
    private val orderService: OrderService,
    private val couponService: CouponService,
    private val checkoutRepository: CheckoutRepository,
) {
    private val logger = LoggerFactory.getLogger(PaymentController::class.java)

    private companion object {
        const val MAX_QUANTITY = 20
    }

    @PostMapping("/create-order")
    fun createOrder(
        @RequestBody request: CreateOrderRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val userId = UUID.fromString(jwt.subject)
        // Ownership — you can only pay for your own trip (throws → 404 if not).
        tripService.getTrip(request.tripId, userId)

        val quantity = (request.quantity ?: 1).coerceIn(1, MAX_QUANTITY)
        // Price is computed here, server-side — the client cannot choose the amount.
        val listMinor = quantity * Pricing.UNIT_PRICE_MINOR

        return try {
            // Server-side coupon validation + the amount we expect Razorpay to capture.
            val validation = request.couponCode?.takeIf { it.isNotBlank() }
                ?.let { couponService.validate(it, listMinor) }
            if (validation != null && !validation.valid) {
                return ResponseEntity.badRequest().body(mapOf("error" to (validation.message ?: "Coupon is not valid")))
            }
            val expectedCaptured = validation?.finalMinor ?: listMinor

            val order = paymentService.createOrder(
                amountMinor = listMinor,
                currency = "INR",
                receipt = request.receipt,
                offerIds = validation?.offerId?.let { listOf(it) },
                forceOffer = validation?.forceOffer ?: false,
            )
            // Bind the order to this trip/user/qty/expected-amount so verify can't be tampered with.
            checkoutRepository.save(
                Checkout(
                    razorpayOrderId = order.orderId,
                    tripId = request.tripId,
                    userId = userId,
                    quantity = quantity,
                    amountMinor = expectedCaptured,
                    currency = "INR",
                    couponCode = request.couponCode?.takeIf { it.isNotBlank() },
                    addressLine1 = request.addressLine1?.ifBlank { null },
                    addressLine2 = request.addressLine2?.ifBlank { null },
                    city = request.city?.ifBlank { null },
                    state = request.state?.ifBlank { null },
                    pincode = request.pincode?.ifBlank { null },
                    shipCountry = request.country?.ifBlank { null },
                    phone = request.phone?.ifBlank { null },
                )
            )
            ResponseEntity.ok(CreateOrderResponse(order.orderId, order.amount, order.currency))
        } catch (ex: RazorpayAuthException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Razorpay authentication failed"))
        } catch (ex: RazorpayException) {
            if (!request.couponCode.isNullOrBlank()) {
                logger.warn("Razorpay rejected order with coupon {}: {}", request.couponCode, ex.message)
                ResponseEntity.badRequest().body(mapOf("error" to "This coupon can't be applied to your order"))
            } else {
                logger.error("Failed to create Razorpay order", ex)
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "Failed to create order"))
            }
        } catch (ex: Exception) {
            logger.error("Failed to create Razorpay order", ex)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "Failed to create order"))
        }
    }

    @PostMapping("/verify")
    fun verifyPayment(
        @RequestBody request: VerifyPaymentRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Any> {
        val orderId = request.razorpayOrderId
        val paymentId = request.razorpayPaymentId
        val signature = request.razorpaySignature
        if (orderId.isNullOrBlank() || paymentId.isNullOrBlank() || signature.isNullOrBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Missing required fields"))
        }

        if (!paymentService.verifySignature(orderId, paymentId, signature)) {
            logger.warn("Razorpay signature mismatch for order {}", orderId)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("verified" to false, "error" to "Signature verification failed"))
        }

        val userId = UUID.fromString(jwt.subject)
        // Trust the server-side binding, NOT client-supplied trip/quantity/amount.
        val checkout = checkoutRepository.findByRazorpayOrderId(orderId)
            ?: return ResponseEntity.badRequest().body(mapOf("verified" to false, "error" to "Unknown or expired checkout"))
        if (checkout.userId != userId) {
            logger.warn("Checkout ownership mismatch for order {} (user {})", orderId, userId)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("verified" to false, "error" to "Not your checkout"))
        }

        return try {
            // Re-verifies the captured payment (status/amount/order) and records the order,
            // flips the trip to ORDERED, and closes the checkout — all in one transaction.
            // Shipping is read from the checkout (persisted at create-order time).
            orderService.recordPaidOrder(checkout, paymentId)
            ResponseEntity.ok(mapOf("verified" to true))
        } catch (ex: DataIntegrityViolationException) {
            // The webhook recorded this exact payment concurrently — the order still exists.
            logger.info("Order for payment {} already recorded (concurrent webhook); treating as verified", paymentId)
            ResponseEntity.ok(mapOf("verified" to true))
        } catch (ex: PaymentVerificationException) {
            logger.warn("Payment verification failed for order {}: {}", orderId, ex.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("verified" to false, "error" to (ex.message ?: "Payment could not be verified")))
        }
    }
}
