package com.atlaso.service

import com.atlaso.controller.ValidatedShipping
import com.atlaso.domain.order.Checkout
import com.atlaso.domain.order.Order
import com.atlaso.domain.trip.TripStatus
import com.atlaso.domain.user.FREE_PREVIEW_QUOTA
import com.atlaso.repository.CheckoutRepository
import com.atlaso.repository.OrderRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

private const val UNIT_PRICE_MINOR = Pricing.UNIT_PRICE_MINOR // Rs. 1999 in paise

/** Thrown when a captured payment fails server-side verification (status/amount/order/ownership). */
class PaymentVerificationException(message: String) : RuntimeException(message)

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val checkoutRepository: CheckoutRepository,
    private val userRepository: UserRepository,
    private val tripRepository: TripRepository,
    private val tripService: TripService,
    private val bookGenerationService: BookGenerationService,
    private val paymentService: PaymentService,
    private val couponService: CouponService,
    private val receiptRenderer: ReceiptRenderer,
    private val orderNotificationService: OrderNotificationService,
    private val slackNotifier: SlackNotifier,
) {
    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    /**
     * Records a paid order from a server-side [checkout] binding after signature verification.
     * Re-verifies the captured payment against Razorpay (status = captured, order match, amount
     * ≥ expected, currency), then — in one transaction — creates the order, flips the trip to
     * ORDERED, and closes the checkout. Idempotent on the payment id (app- and DB-level), so a
     * payment can't be replayed into multiple orders or against multiple trips.
     *
     * Shipping is read from the [checkout] (persisted at create-order time), so this is called
     * identically from the browser-driven /verify path and the Razorpay webhook safety net.
     *
     * Concurrency: /verify and the webhook can call this for the same payment at nearly the same
     * instant. Both pass the early [findByRazorpayPaymentId] guard, then race on the INSERT; the
     * `ux_orders_razorpay_payment_id` unique index lets exactly one win. The loser surfaces a
     * [org.springframework.dao.DataIntegrityViolationException] — callers treat that as
     * "already recorded" (see PaymentController/PaymentWebhookController).
     */
    @Transactional
    fun recordPaidOrder(checkout: Checkout, razorpayPaymentId: String): Order {
        orderRepository.findByRazorpayPaymentId(razorpayPaymentId)?.let { return it }

        val payment = paymentService.fetchPayment(razorpayPaymentId)
            ?: throw PaymentVerificationException("Could not verify payment with Razorpay")
        if (payment.status != "captured") throw PaymentVerificationException("Payment is not captured")
        // Require exact, non-null matches from the provider — never accept missing fields.
        if (payment.orderId == null || payment.orderId != checkout.razorpayOrderId)
            throw PaymentVerificationException("Payment does not belong to this order")
        val captured = payment.amountMinor
            ?: throw PaymentVerificationException("Captured amount missing")
        if (captured < checkout.amountMinor)
            throw PaymentVerificationException("Captured amount is below the order amount")
        if (payment.currency == null || !payment.currency.equals(checkout.currency, ignoreCase = true))
            throw PaymentVerificationException("Currency mismatch")

        val tripId = checkout.tripId
        val userId = checkout.userId
        val trip = tripService.getTrip(tripId, userId) // ownership re-check
        val user = userRepository.findById(userId).orElse(null)

        val qty = checkout.quantity
        val listMinor = qty * UNIT_PRICE_MINOR
        val amountMinor = captured // authoritative captured amount from Razorpay
        val coupon = checkout.couponCode?.takeIf { it.isNotBlank() }?.let { couponService.findByCode(it) }
        val discountMinor = coupon?.let { (listMinor - amountMinor).takeIf { d -> d > 0 } }
        val book = runCatching { bookGenerationService.getLatestBookByTripId(tripId, userId) }.getOrNull()

        val order = Order(
            number = orderRepository.nextNumber(),
            trip = trip,
            bookId = book?.id,
            bookTitle = book?.title,
            razorpayOrderId = checkout.razorpayOrderId,
            razorpayPaymentId = razorpayPaymentId,
            paymentMethod = payment?.method,
            amountMinor = amountMinor,
            currency = "INR",
            quantity = qty,
            customerName = user?.name,
            customerEmail = user?.email ?: payment?.email,
            addressLine1 = checkout.addressLine1?.ifBlank { null },
            addressLine2 = checkout.addressLine2?.ifBlank { null },
            city = checkout.city?.ifBlank { null },
            state = checkout.state?.ifBlank { null },
            pincode = checkout.pincode?.ifBlank { null },
            shipCountry = checkout.shipCountry?.ifBlank { null },
            phone = checkout.phone?.ifBlank { null },
            couponCode = coupon?.code,
            razorpayOfferId = coupon?.razorpayOfferId,
            discountMinor = discountMinor,
            status = "PAID",
        )
        val saved = orderRepository.save(order)

        // Same transaction: flip the trip to ORDERED and close the checkout, so a payment
        // only ever marks the ONE bound trip (and only once).
        tripService.updateStatus(tripId, TripStatus.ORDERED)
        checkout.status = "COMPLETED"
        checkoutRepository.save(checkout)
        // A paid order refills the buyer's free book-preview quota back to the full allowance.
        userRepository.resetFreePreviews(userId, FREE_PREVIEW_QUOTA)
        logger.info("Recorded order ATL-{} for trip {}", saved.number, tripId)

        // Count the redemption against the coupon's usage cap — best-effort.
        coupon?.let { runCatching { couponService.recordRedemption(it) } }

        // Receipt render + confirmation email run AFTER commit, off the request thread — so the
        // Razorpay webhook responds within its 5s budget and no email is sent for an order that
        // ends up rolling back.
        afterCommit { orderNotificationService.sendOrderConfirmation(saved) }
        // Ping #orders once the order is durably committed.
        afterCommit { slackNotifier.notifyOrder(saved) }

        return saved
    }

    /**
     * Records a free order for a full-discount ("100% off") coupon, with no payment. Mirrors
     * [recordPaidOrder]'s side effects — trip → ORDERED, ₹0 receipt + confirmation email, Slack
     * #orders ping, free-preview reset, coupon-usage bump — minus the Razorpay verification.
     *
     * Called straight from create-order (no Checkout, no /verify, no webhook). The coupon is
     * re-resolved and asserted full-discount here so the client can never self-grant a free order.
     * Idempotent on the trip: a trip is ordered once, so a double-submit returns the existing order.
     */
    @Transactional
    fun recordFreeOrder(
        tripId: UUID,
        userId: UUID,
        quantity: Int,
        couponCode: String,
        shipping: ValidatedShipping,
    ): Order {
        // Serialize free-order creation on the trip row so two concurrent double-submits can't both
        // read "no order" and each insert a separate ₹0 order (orders has no per-trip unique key).
        tripRepository.findByIdForUpdate(tripId)

        // A trip is only ever ordered once — return the existing order on a retry/double-submit.
        orderRepository.findFirstByTripIdOrderByCreatedAtDesc(tripId)
            ?.takeIf { it.trip.user?.id == userId }
            ?.let { return it }

        val trip = tripService.getTrip(tripId, userId) // ownership re-check
        val user = userRepository.findById(userId).orElse(null)
        val qty = quantity
        val listMinor = qty * UNIT_PRICE_MINOR

        // Authoritative re-validation: only a genuine, eligible full-discount coupon reaches here.
        val coupon = couponService.resolveForOrder(couponCode, listMinor)
        if (!coupon.fullDiscount) throw CouponInvalidException("Coupon does not grant a free order")

        // Reserve the redemption atomically INSIDE this transaction, before creating the order. The
        // conditional UPDATE enforces maxUses under concurrency (unlike the best-effort counter on the
        // paid path); if we're at the cap it reserves nothing and we abort — rolling back the order.
        if (!couponService.tryReserveRedemption(coupon)) {
            throw CouponInvalidException("This coupon has been fully redeemed")
        }

        val book = runCatching { bookGenerationService.getLatestBookByTripId(tripId, userId) }.getOrNull()

        val order = Order(
            number = orderRepository.nextNumber(),
            trip = trip,
            bookId = book?.id,
            bookTitle = book?.title,
            razorpayOrderId = null,
            razorpayPaymentId = null,
            paymentMethod = null,
            amountMinor = 0,
            currency = "INR",
            quantity = qty,
            customerName = user?.name,
            customerEmail = user?.email,
            addressLine1 = shipping.addressLine1,
            addressLine2 = shipping.addressLine2,
            city = shipping.city,
            state = shipping.state,
            pincode = shipping.pincode,
            shipCountry = shipping.country,
            phone = shipping.phone,
            couponCode = coupon.code,
            razorpayOfferId = null,
            discountMinor = listMinor, // the full list price was waived
            status = "PAID",
        )
        val saved = orderRepository.save(order)

        tripService.updateStatus(tripId, TripStatus.ORDERED)
        userRepository.resetFreePreviews(userId, FREE_PREVIEW_QUOTA)
        logger.info("Recorded FREE order ATL-{} for trip {} (coupon {})", saved.number, tripId, coupon.code)

        // Redemption was already reserved atomically above (no best-effort bump here).
        afterCommit { orderNotificationService.sendOrderConfirmation(saved) }
        afterCommit { slackNotifier.notifyOrder(saved) }

        return saved
    }

    /** The recorded order for a Razorpay payment id, if one exists. Used by the /verify and
     *  webhook callers to distinguish "the other path already inserted this payment" from an
     *  unrelated integrity failure after catching a DataIntegrityViolationException. */
    fun findRecordedOrder(razorpayPaymentId: String): Order? =
        orderRepository.findByRazorpayPaymentId(razorpayPaymentId)

    /** Runs [action] after the current transaction commits (or immediately if none is active). */
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action()
            })
        } else {
            action()
        }
    }

    /** Generates the receipt PDF for the latest order on [tripId], owner-checked. */
    /** The latest paid order for a trip, verified to belong to the user. */
    fun getOrderForTrip(tripId: UUID, userId: UUID): Order? {
        val order = orderRepository.findFirstByTripIdOrderByCreatedAtDesc(tripId) ?: return null
        if (order.trip.user?.id != userId) return null
        return order
    }

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
