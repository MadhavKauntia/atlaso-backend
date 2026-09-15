package com.atlaso.controller

import com.atlaso.domain.order.Checkout
import com.atlaso.domain.order.Order
import com.atlaso.repository.CheckoutRepository
import com.atlaso.service.OrderService
import com.atlaso.service.PaymentService
import com.atlaso.service.PaymentVerificationException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

class PaymentWebhookControllerTest {

    private val paymentService = mock<PaymentService>()
    private val orderService = mock<OrderService>()
    private val checkoutRepo = mock<CheckoutRepository>()
    private val controller = PaymentWebhookController(paymentService, orderService, checkoutRepo, ObjectMapper())

    private val capturedBody =
        """{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_1","order_id":"order_1","status":"captured"}}}}"""

    private fun checkout() = Checkout(
        id = UUID.randomUUID(), razorpayOrderId = "order_1", tripId = UUID.randomUUID(),
        userId = UUID.randomUUID(), quantity = 1, amountMinor = 199900,
    )

    @BeforeEach
    fun base() {
        whenever(paymentService.isWebhookConfigured()).thenReturn(true)
    }

    @Test
    fun `rejects when webhook secret is not configured`() {
        whenever(paymentService.isWebhookConfigured()).thenReturn(false)
        val res = controller.handleWebhook(capturedBody, "sig", "evt_1")
        assertEquals(400, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `rejects an invalid signature and records nothing`() {
        whenever(paymentService.verifyWebhookSignature(capturedBody, "sig")).thenReturn(false)
        val res = controller.handleWebhook(capturedBody, "sig", "evt_1")
        assertEquals(400, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `ignores an unsubscribed event type with 200`() {
        whenever(paymentService.verifyWebhookSignature(any(), any())).thenReturn(true)
        val body = """{"event":"payment.authorized","payload":{"payment":{"entity":{"id":"pay_1","order_id":"order_1"}}}}"""
        val res = controller.handleWebhook(body, "sig", "evt_1")
        assertEquals(200, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `acknowledges an unknown order with 200 and records nothing`() {
        whenever(paymentService.verifyWebhookSignature(any(), any())).thenReturn(true)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(null)
        val res = controller.handleWebhook(capturedBody, "sig", "evt_1")
        assertEquals(200, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `records the order for a known checkout and returns 200`() {
        whenever(paymentService.verifyWebhookSignature(any(), any())).thenReturn(true)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(checkout())
        whenever(orderService.recordPaidOrder(any(), any())).thenReturn(mock<Order>())
        val res = controller.handleWebhook(capturedBody, "sig", "evt_1")
        assertEquals(200, res.statusCode.value())
        verify(orderService).recordPaidOrder(any(), any())
    }

    @Test
    fun `treats a concurrently-recorded payment as success`() {
        whenever(paymentService.verifyWebhookSignature(any(), any())).thenReturn(true)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(checkout())
        whenever(orderService.recordPaidOrder(any(), any())).thenThrow(DataIntegrityViolationException("dup"))
        val res = controller.handleWebhook(capturedBody, "sig", "evt_1")
        assertEquals(200, res.statusCode.value())
    }

    @Test
    fun `returns 500 on a transient verification failure so Razorpay retries`() {
        whenever(paymentService.verifyWebhookSignature(any(), any())).thenReturn(true)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(checkout())
        whenever(orderService.recordPaidOrder(any(), any())).thenThrow(PaymentVerificationException("razorpay down"))
        val res = controller.handleWebhook(capturedBody, "sig", "evt_1")
        assertEquals(500, res.statusCode.value())
    }
}
