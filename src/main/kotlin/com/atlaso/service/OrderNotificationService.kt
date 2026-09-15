package com.atlaso.service

import com.atlaso.domain.order.Order
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Sends the order confirmation email (with the rendered receipt) off the request thread.
 *
 * This is deliberately a separate bean invoked AFTER the order transaction commits: Razorpay
 * requires a 2xx webhook response within 5s, and rendering a PDF + calling Brevo is slow enough
 * to risk that budget. Running it async also guarantees no email goes out for an order that
 * ultimately rolled back. Best-effort — a failure here never affects the recorded order.
 */
@Service
class OrderNotificationService(
    private val receiptRenderer: ReceiptRenderer,
    private val emailService: EmailService,
) {
    private val logger = LoggerFactory.getLogger(OrderNotificationService::class.java)

    @Async("orderNotificationExecutor")
    fun sendOrderConfirmation(order: Order) {
        runCatching {
            val receipt = receiptRenderer.render(order)
            emailService.sendOrderConfirmation(order, receipt)
        }.onFailure { logger.error("Order confirmation email failed for ATL-{}", order.number, it) }
    }
}
