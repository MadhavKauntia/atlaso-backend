package com.atlaso.service

import com.atlaso.controller.dto.AdminOrderDto
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
    private val storageService: StorageService,
) {
    fun listOrders(): List<AdminOrderDto> =
        orderRepository.findAllByOrderByCreatedAtDesc().map { AdminOrderDto.from(it) }

    @Transactional
    fun markShipped(orderId: UUID, trackingNumber: String?): AdminOrderDto {
        val order = orderRepository.findById(orderId).orElseThrow { RuntimeException("Order not found") }
        order.status = "SHIPPED"
        order.shippedAt = Instant.now()
        order.trackingNumber = trackingNumber?.ifBlank { null }
        return AdminOrderDto.from(orderRepository.save(order))
    }

    fun receiptPdf(orderId: UUID): Pair<ByteArray, String> {
        val order = orderRepository.findById(orderId).orElseThrow { RuntimeException("Order not found") }
        return receiptRenderer.render(order) to "atlaso-receipt-ATL-R-${order.number}.pdf"
    }

    fun bookPdf(orderId: UUID): Pair<ByteArray, String> {
        val order = orderRepository.findById(orderId).orElseThrow { RuntimeException("Order not found") }
        val bookId = order.bookId ?: throw IllegalStateException("No book linked to this order")
        val book = bookRepository.findById(bookId).orElseThrow { RuntimeException("Book not found") }
        if (book.pdfUrl == null) throw IllegalStateException("Book PDF is not available")
        val bytes = storageService.load("pdfs/${bookId}/photobook.pdf")
        val filename = "atlaso-${order.number}-${book.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.pdf"
        return bytes to filename
    }
}
