package com.atlaso.service

import com.atlaso.controller.dto.AdminOrderDto
import com.atlaso.domain.book.Book
import com.atlaso.domain.order.Order
import com.atlaso.repository.BookRepository
import com.atlaso.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AdminService(
    private val orderRepository: OrderRepository,
    private val bookRepository: BookRepository,
    private val receiptRenderer: ReceiptRenderer,
    private val printPdfStore: PrintPdfStore,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(AdminService::class.java)

    fun listOrders(): List<AdminOrderDto> =
        orderRepository.findAllByOrderByCreatedAtDesc().map { AdminOrderDto.from(it) }

    @Transactional
    fun markShipped(orderId: UUID, trackingNumber: String?): AdminOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { RuntimeException("Order not found") }
        order.status = "SHIPPED"
        order.shippedAt = Instant.now()
        order.trackingNumber = trackingNumber?.ifBlank { null }
        val saved = orderRepository.save(order)
        logger.info("Order ATL-{} marked SHIPPED (tracking: {})", saved.number, saved.trackingNumber ?: "none")
        return AdminOrderDto.from(saved)
    }

    fun receiptPdf(orderId: UUID): Pair<ByteArray, String> {
        val order = orderRepository.findById(orderId).orElseThrow { RuntimeException("Order not found") }
        return receiptRenderer.render(order) to "atlaso-receipt-ATL-R-${order.number}.pdf"
    }

    /** The single print PDF for an order's book: front cover, interior pages, back cover. */
    fun bookPdf(orderId: UUID): Pair<ByteArray, String> {
        val (order, book) = orderAndBook(orderId)
        return printPdfStore.load(book) to "atlaso-${order.number}-${safeTitle(book)}.pdf"
    }

    private fun orderAndBook(orderId: UUID): Pair<Order, Book> {
        val order = orderRepository.findById(orderId).orElseThrow { RuntimeException("Order not found") }
        val bookId = order.bookId ?: throw IllegalStateException("No book linked to this order")
        val book = bookRepository.findById(bookId).orElseThrow { RuntimeException("Book not found") }
        if (book.pdfUrl == null) throw IllegalStateException("Book PDF is not available")
        return order to book
    }

    private fun safeTitle(book: Book): String = book.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
