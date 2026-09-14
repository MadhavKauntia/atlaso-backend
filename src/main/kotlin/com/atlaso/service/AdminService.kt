package com.atlaso.service

import com.atlaso.controller.dto.AdminOrderDto
import com.atlaso.domain.book.Book
import com.atlaso.domain.order.Order
import com.atlaso.repository.BookRepository
import com.atlaso.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val (order, book) = orderAndBook(orderId)
        val bytes = storageService.load("pdfs/${book.id}/photobook.pdf")
        return bytes to "atlaso-${order.number}-${safeTitle(book)}.pdf"
    }

    fun coverPdf(orderId: UUID): Pair<ByteArray, String> {
        val (order, book) = orderAndBook(orderId)
        val bytes = storageService.load("pdfs/${book.id}/cover.pdf")
        return bytes to "atlaso-${order.number}-cover.pdf"
    }

    /** Cover + interior PDFs bundled together for the print handoff. */
    fun pdfsZip(orderId: UUID): Pair<ByteArray, String> {
        val (order, book) = orderAndBook(orderId)
        val cover = storageService.load("pdfs/${book.id}/cover.pdf")
        val interior = storageService.load("pdfs/${book.id}/photobook.pdf")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("atlaso-${order.number}-cover.pdf"))
            zip.write(cover)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("atlaso-${order.number}-${safeTitle(book)}.pdf"))
            zip.write(interior)
            zip.closeEntry()
        }
        return out.toByteArray() to "atlaso-${order.number}-pdfs.zip"
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
