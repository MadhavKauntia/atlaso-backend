package com.atlaso.controller

import com.atlaso.repository.CheckoutRepository
import com.atlaso.service.OrderService
import com.atlaso.service.PaymentService
import com.atlaso.service.PaymentVerificationException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Razorpay webhook — the server-to-server safety net that confirms payments even when the
 * buyer's browser dies before the client-side /verify call lands. Razorpay retries delivery
 * for up to 24h, so a captured payment always turns into an order.
 *
 * Auth: this endpoint is NOT behind JWT (Razorpay sends no bearer token). It is authenticated
 * solely by the HMAC-SHA256 signature over the raw body in `X-Razorpay-Signature`, keyed by the
 * dashboard webhook secret. The endpoint is permitAll-ed in SecurityConfig.
 *
 * Response contract:
 *   200 — handled, duplicate, unknown order, or ignored event (stop retrying)
 *   400 — bad/missing signature or webhook not configured (won't be retried; misconfiguration)
 *   500 — transient failure recording the order (Razorpay should retry)
 */
@RestController
@RequestMapping("/api/payments")
class PaymentWebhookController(
    private val paymentService: PaymentService,
    private val orderService: OrderService,
    private val checkoutRepository: CheckoutRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(PaymentWebhookController::class.java)

    private companion object {
        const val EVENT_PAYMENT_CAPTURED = "payment.captured"
    }

    @PostMapping("/webhook")
    fun handleWebhook(
        @RequestBody rawBody: String,
        @RequestHeader(value = "X-Razorpay-Signature", required = false) signature: String?,
        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) eventId: String?,
    ): ResponseEntity<Any> {
        // eventId is Razorpay's delivery id — logged throughout so a single delivery can be
        // traced end-to-end across retries in Axiom.
        val trace = eventId ?: "no-event-id"

        if (!paymentService.isWebhookConfigured()) {
            logger.error("Razorpay webhook received but RAZORPAY_WEBHOOK_SECRET is not set [event={}]", trace)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "webhook not configured"))
        }

        if (!paymentService.verifyWebhookSignature(rawBody, signature)) {
            logger.warn("Razorpay webhook signature verification FAILED [event={}, hasSignature={}]", trace, signature != null)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "invalid signature"))
        }

        val root = try {
            objectMapper.readTree(rawBody)
        } catch (ex: Exception) {
            logger.warn("Razorpay webhook body was not valid JSON [event={}]: {}", trace, ex.message)
            // Acknowledge — retrying won't fix a malformed body.
            return ResponseEntity.ok(mapOf("status" to "ignored: unparseable"))
        }

        val eventType = root.get("event")?.asText()
        if (eventType != EVENT_PAYMENT_CAPTURED) {
            logger.info("Razorpay webhook ignored — unsubscribed event '{}' [event={}]", eventType, trace)
            return ResponseEntity.ok(mapOf("status" to "ignored: $eventType"))
        }

        val paymentNode = root.path("payload").path("payment").path("entity")
        val paymentId = paymentNode.get("id")?.asText()?.takeIf { it.isNotBlank() }
        val orderId = paymentNode.get("order_id")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
        if (paymentId == null || orderId == null) {
            logger.warn(
                "Razorpay webhook '{}' missing payment/order id [event={}, paymentId={}, orderId={}]",
                eventType, trace, paymentId, orderId,
            )
            // Nothing actionable — acknowledge so Razorpay stops retrying.
            return ResponseEntity.ok(mapOf("status" to "ignored: missing ids"))
        }

        logger.info("Razorpay webhook '{}' received [event={}, payment={}, order={}]", eventType, trace, paymentId, orderId)

        val checkout = checkoutRepository.findByRazorpayOrderId(orderId)
        if (checkout == null) {
            // Order we don't recognise — a test event, a different environment, or a stale order.
            // Ack so Razorpay stops retrying; log loudly so it's visible if it's unexpected.
            logger.warn("Razorpay webhook for unknown checkout [event={}, order={}, payment={}]", trace, orderId, paymentId)
            return ResponseEntity.ok(mapOf("status" to "ignored: unknown order"))
        }

        return try {
            val order = orderService.recordPaidOrder(checkout, paymentId)
            logger.info(
                "Razorpay webhook recorded order ATL-{} [event={}, trip={}, payment={}]",
                order.number, trace, checkout.tripId, paymentId,
            )
            ResponseEntity.ok(mapOf("status" to "ok"))
        } catch (ex: DataIntegrityViolationException) {
            // /verify (or a prior webhook delivery) already recorded this exact payment.
            logger.info("Razorpay webhook: payment {} already recorded [event={}] — no-op", paymentId, trace)
            ResponseEntity.ok(mapOf("status" to "ok: already recorded"))
        } catch (ex: PaymentVerificationException) {
            // Could be transient (Razorpay fetch failed) — return 500 so Razorpay retries.
            logger.error(
                "Razorpay webhook could not record order [event={}, order={}, payment={}]: {}",
                trace, orderId, paymentId, ex.message,
            )
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "could not record order"))
        } catch (ex: Exception) {
            logger.error("Razorpay webhook unexpected error [event={}, order={}, payment={}]", trace, orderId, paymentId, ex)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "internal error"))
        }
    }
}
