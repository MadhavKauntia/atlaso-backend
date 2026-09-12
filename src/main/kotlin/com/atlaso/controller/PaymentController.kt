package com.atlaso.controller

import com.atlaso.controller.dto.CreateOrderRequest
import com.atlaso.controller.dto.CreateOrderResponse
import com.atlaso.controller.dto.VerifyPaymentRequest
import com.atlaso.domain.trip.TripStatus
import com.atlaso.service.OrderService
import com.atlaso.service.ShippingInput
import com.atlaso.service.PaymentService
import com.atlaso.service.RazorpayAuthException
import com.atlaso.service.TripService
import org.slf4j.LoggerFactory
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
) {
    private val logger = LoggerFactory.getLogger(PaymentController::class.java)

    @PostMapping("/create-order")
    fun createOrder(@RequestBody request: CreateOrderRequest): ResponseEntity<Any> {
        if (request.amount < 100) {
            return ResponseEntity.badRequest().body(mapOf("error" to "amount must be at least 100 paise"))
        }
        return try {
            val order = paymentService.createOrder(
                amountMinor = request.amount,
                currency = request.currency ?: "INR",
                receipt = request.receipt,
            )
            ResponseEntity.ok(CreateOrderResponse(order.orderId, order.amount, order.currency))
        } catch (ex: RazorpayAuthException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Razorpay authentication failed"))
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

        val valid = paymentService.verifySignature(orderId, paymentId, signature)
        if (!valid) {
            logger.warn("Razorpay signature mismatch for order {}", orderId)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("verified" to false, "error" to "Signature verification failed"))
        }

        // Signature is valid — mark the trip ordered (validating ownership first)
        // and record the order for the receipt.
        request.tripId?.let { tripId ->
            val userId = UUID.fromString(jwt.subject)
            tripService.getTrip(tripId, userId) // throws if not owned by this user
            tripService.updateStatus(tripId, TripStatus.ORDERED)
            // Best-effort — the payment already succeeded, so a recording failure
            // must not surface as a failed verification.
            try {
                val shipping = ShippingInput(
                    addressLine1 = request.addressLine1,
                    addressLine2 = request.addressLine2,
                    city = request.city,
                    state = request.state,
                    pincode = request.pincode,
                    country = request.country,
                    phone = request.phone,
                )
                orderService.createPaidOrder(tripId, userId, orderId, paymentId, request.quantity, shipping)
            } catch (ex: Exception) {
                logger.error("Failed to record order for trip {}", tripId, ex)
            }
        }

        return ResponseEntity.ok(mapOf("verified" to true))
    }
}
