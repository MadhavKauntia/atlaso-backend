package com.atlaso.controller

import com.atlaso.controller.dto.CreateOrderRequest
import com.atlaso.controller.dto.VerifyPaymentRequest
import com.atlaso.domain.order.Checkout
import com.atlaso.repository.CheckoutRepository
import com.atlaso.service.CouponService
import com.atlaso.service.OrderService
import com.atlaso.service.PaymentService
import com.atlaso.service.TripService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

class PaymentControllerTest {

    private val paymentService = mock<PaymentService>()
    private val tripService = mock<TripService>()
    private val orderService = mock<OrderService>()
    private val couponService = mock<CouponService>()
    private val checkoutRepo = mock<CheckoutRepository>()
    private val controller = PaymentController(paymentService, tripService, orderService, couponService, checkoutRepo)

    private val userId = UUID.randomUUID()
    private val jwt = mock<Jwt> { whenever(it.subject).thenReturn(userId.toString()) }

    private fun req() = VerifyPaymentRequest(razorpayOrderId = "order_1", razorpayPaymentId = "pay_1", razorpaySignature = "sig")

    private fun checkout(owner: UUID) = Checkout(
        id = UUID.randomUUID(), razorpayOrderId = "order_1", tripId = UUID.randomUUID(),
        userId = owner, quantity = 1, amountMinor = 249900
    )

    @Test
    fun `rejects an invalid signature and records nothing`() {
        whenever(paymentService.verifySignature("order_1", "pay_1", "sig")).thenReturn(false)
        val res = controller.verifyPayment(req(), jwt)
        assertEquals(400, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `rejects an unknown checkout`() {
        whenever(paymentService.verifySignature(any(), any(), any())).thenReturn(true)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(null)
        val res = controller.verifyPayment(req(), jwt)
        assertEquals(400, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `forbids verifying someone else's checkout`() {
        whenever(paymentService.verifySignature(any(), any(), any())).thenReturn(true)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(checkout(owner = UUID.randomUUID()))
        val res = controller.verifyPayment(req(), jwt)
        assertEquals(403, res.statusCode.value())
        verify(orderService, never()).recordPaidOrder(any(), any())
    }

    @Test
    fun `records the order for the owner's valid checkout`() {
        whenever(paymentService.verifySignature(any(), any(), any())).thenReturn(true)
        val c = checkout(owner = userId)
        whenever(checkoutRepo.findByRazorpayOrderId("order_1")).thenReturn(c)
        val res = controller.verifyPayment(req(), jwt)
        assertEquals(200, res.statusCode.value())
        verify(orderService).recordPaidOrder(any(), any())
    }

    // --- create-order shipping validation ---

    private fun orderReq(
        addressLine1: String? = "12 MG Road", city: String? = "Bengaluru", state: String? = "Karnataka",
        pincode: String? = "560001", country: String? = "India", phone: String? = "+919876543210",
    ) = CreateOrderRequest(
        tripId = UUID.randomUUID(), quantity = 1, addressLine1 = addressLine1, city = city,
        state = state, pincode = pincode, country = country, phone = phone,
    )

    @Test
    fun `create-order rejects missing shipping and never creates a Razorpay order`() {
        val res = controller.createOrder(orderReq(addressLine1 = null), jwt)
        assertEquals(400, res.statusCode.value())
        verify(paymentService, never()).createOrder(any(), any(), any(), any(), any())
        verify(checkoutRepo, never()).save(any())
    }

    @Test
    fun `create-order rejects an invalid pincode and never creates a Razorpay order`() {
        val res = controller.createOrder(orderReq(pincode = "12"), jwt)
        assertEquals(400, res.statusCode.value())
        verify(paymentService, never()).createOrder(any(), any(), any(), any(), any())
        verify(checkoutRepo, never()).save(any())
    }
}
