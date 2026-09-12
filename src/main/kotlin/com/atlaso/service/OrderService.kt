package com.atlaso.service

import com.atlaso.domain.order.Order
import com.atlaso.repository.OrderRepository
import com.atlaso.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val UNIT_PRICE_MINOR = 199900L // Rs. 1999 in paise

/** Shipping details captured at checkout (recipient name/email come from the account). */
data class ShippingInput(
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val country: String? = null,
    val phone: String? = null,
)

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val tripService: TripService,
    private val bookGenerationService: BookGenerationService,
    private val paymentService: PaymentService,
    private val receiptRenderer: ReceiptRenderer,
    private val emailService: EmailService,
) {
    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    /**
     * Records a paid order after a verified payment. Idempotent on the Razorpay
     * payment id. Ownership of [tripId] must already have been validated.
     */
    @Transactional
    fun createPaidOrder(
        tripId: UUID,
        userId: UUID,
        razorpayOrderId: String?,
        razorpayPaymentId: String?,
        quantity: Int?,
        shipping: ShippingInput? = null,
    ): Order {
        razorpayPaymentId?.let { pid ->
            orderRepository.findByRazorpayPaymentId(pid)?.let { return it }
        }

        val trip = tripService.getTrip(tripId, userId) // throws if not owned
        val user = userRepository.findById(userId).orElse(null)
        val payment = razorpayPaymentId?.let { paymentService.fetchPayment(it) }

        val qty = (quantity ?: 1).coerceAtLeast(1)
        val amountMinor = payment?.amountMinor ?: (qty * UNIT_PRICE_MINOR)
        val book = runCatching { bookGenerationService.getLatestBookByTripId(tripId, userId) }.getOrNull()

        val order = Order(
            number = orderRepository.nextNumber(),
            trip = trip,
            bookId = book?.id,
            bookTitle = book?.title,
            razorpayOrderId = razorpayOrderId,
            razorpayPaymentId = razorpayPaymentId,
            paymentMethod = payment?.method,
            amountMinor = amountMinor,
            currency = "INR",
            quantity = qty,
            customerName = user?.name,
            customerEmail = user?.email ?: payment?.email,
            addressLine1 = shipping?.addressLine1?.ifBlank { null },
            addressLine2 = shipping?.addressLine2?.ifBlank { null },
            city = shipping?.city?.ifBlank { null },
            state = shipping?.state?.ifBlank { null },
            pincode = shipping?.pincode?.ifBlank { null },
            shipCountry = shipping?.country?.ifBlank { null },
            phone = shipping?.phone?.ifBlank { null },
            status = "PAID",
        )
        val saved = orderRepository.save(order)
        logger.info("Recorded order ATL-{} for trip {}", saved.number, tripId)

        // Best-effort order confirmation email with the receipt attached.
        runCatching {
            val receipt = receiptRenderer.render(saved)
            emailService.sendOrderConfirmation(saved, receipt)
        }.onFailure { logger.error("Order confirmation email failed for ATL-{}", saved.number, it) }

        return saved
    }

    /** Generates the receipt PDF for the latest order on [tripId], owner-checked. */
    fun generateReceipt(tripId: UUID, userId: UUID): Pair<ByteArray, String> {
        val order = orderRepository.findFirstByTripIdOrderByCreatedAtDesc(tripId)
            ?: throw RuntimeException("No order found for trip $tripId")
        if (order.trip.user?.id != userId) {
            throw RuntimeException("No order found for trip $tripId")
        }
        val bytes = receiptRenderer.render(order)
        return bytes to "atlaso-receipt-ATL-R-${order.number}.pdf"
    }
}
