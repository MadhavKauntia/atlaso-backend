package com.atlaso.service

import com.atlaso.domain.order.Checkout
import com.atlaso.domain.order.Order
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.CheckoutRepository
import com.atlaso.repository.OrderRepository
import com.atlaso.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

class OrderServiceTest {

    private val orderRepo = mock<OrderRepository>()
    private val checkoutRepo = mock<CheckoutRepository>()
    private val userRepo = mock<UserRepository>()
    private val tripService = mock<TripService>()
    private val bookGen = mock<BookGenerationService>()
    private val payment = mock<PaymentService>()
    private val coupon = mock<CouponService>()
    private val receipt = mock<ReceiptRenderer>()
    private val notifications = mock<OrderNotificationService>()
    private val slackNotifier = mock<SlackNotifier>()
    private val svc = OrderService(orderRepo, checkoutRepo, userRepo, tripService, bookGen, payment, coupon, receipt, notifications, slackNotifier)

    private val tripId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val expected = 199900L // 1 unit

    private fun checkout() = Checkout(
        id = UUID.randomUUID(), razorpayOrderId = "order_1", tripId = tripId, userId = userId,
        quantity = 1, amountMinor = expected
    )

    private fun pay(status: String = "captured", orderId: String? = "order_1", amount: Long? = expected, currency: String? = "INR") =
        PaymentService.RazorpayPayment(method = "upi", amountMinor = amount, email = null, contact = null, status = status, orderId = orderId, currency = currency)

    @BeforeEach
    fun base() {
        whenever(orderRepo.findByRazorpayPaymentId(any())).thenReturn(null)
    }

    @Test
    fun `rejects a payment that isn't captured`() {
        whenever(payment.fetchPayment(any())).thenReturn(pay(status = "authorized"))
        assertThrows(PaymentVerificationException::class.java) { svc.recordPaidOrder(checkout(), "pay_1") }
    }

    @Test
    fun `rejects a payment bound to a different order`() {
        whenever(payment.fetchPayment(any())).thenReturn(pay(orderId = "order_OTHER"))
        assertThrows(PaymentVerificationException::class.java) { svc.recordPaidOrder(checkout(), "pay_1") }
    }

    @Test
    fun `rejects underpayment`() {
        whenever(payment.fetchPayment(any())).thenReturn(pay(amount = expected - 1))
        assertThrows(PaymentVerificationException::class.java) { svc.recordPaidOrder(checkout(), "pay_1") }
    }

    @Test
    fun `is idempotent — a replayed payment returns the existing order, no trip update`() {
        val existing = mock<Order>()
        whenever(orderRepo.findByRazorpayPaymentId("pay_1")).thenReturn(existing)
        val result = svc.recordPaidOrder(checkout(), "pay_1")
        assertSame(existing, result)
        verify(orderRepo, never()).save(any())
        verify(tripService, never()).updateStatus(any(), any())
    }

    @Test
    fun `on success records the order, marks the trip ORDERED, and closes the checkout`() {
        whenever(payment.fetchPayment(any())).thenReturn(pay())
        whenever(tripService.getTrip(tripId, userId)).thenReturn(Trip(id = tripId, name = "T", status = TripStatus.BOOK_GENERATED))
        whenever(userRepo.findById(userId)).thenReturn(Optional.empty())
        whenever(bookGen.getLatestBookByTripId(tripId, userId)).thenThrow(RuntimeException("no book"))
        whenever(orderRepo.nextNumber()).thenReturn(5L)
        whenever(orderRepo.save(any<Order>())).thenAnswer { it.arguments[0] }
        val c = checkout()

        svc.recordPaidOrder(c, "pay_1")

        verify(orderRepo).save(any<Order>())
        verify(tripService).updateStatus(tripId, TripStatus.ORDERED)
        verify(checkoutRepo).save(c)
        assertEquals("COMPLETED", c.status)
        // Receipt + email dispatched (no active tx in the unit test → afterCommit runs inline).
        verify(notifications).sendOrderConfirmation(any())
        // #orders Slack ping fired for the committed order.
        verify(slackNotifier).notifyOrder(any())
    }

    @Test
    fun `copies the checkout's shipping onto the recorded order`() {
        whenever(payment.fetchPayment(any())).thenReturn(pay())
        whenever(tripService.getTrip(tripId, userId)).thenReturn(Trip(id = tripId, name = "T", status = TripStatus.BOOK_GENERATED))
        whenever(userRepo.findById(userId)).thenReturn(Optional.empty())
        whenever(bookGen.getLatestBookByTripId(tripId, userId)).thenThrow(RuntimeException("no book"))
        whenever(orderRepo.nextNumber()).thenReturn(7L)
        whenever(orderRepo.save(any<Order>())).thenAnswer { it.arguments[0] }
        val c = checkout().copy(
            addressLine1 = "12 MG Road", city = "Bengaluru", state = "Karnataka",
            pincode = "560001", shipCountry = "India", phone = "+919876543210",
        )

        val order = svc.recordPaidOrder(c, "pay_1")

        assertEquals("12 MG Road", order.addressLine1)
        assertEquals("Bengaluru", order.city)
        assertEquals("560001", order.pincode)
        assertEquals("+919876543210", order.phone)
    }
}
