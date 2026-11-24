package com.atlaso.service

import com.atlaso.application.layout.BookPlanExplainer
import com.atlaso.application.layout.PhotoSelector
import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.book.Layout
import com.atlaso.domain.book.Page
import com.atlaso.domain.book.PhotoSlot
import com.atlaso.domain.book.Position
import com.atlaso.domain.book.Size
import com.atlaso.domain.order.Order
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.domain.user.User
import com.atlaso.repository.BookRepository
import com.atlaso.repository.OrderRepository
import com.atlaso.repository.PageRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

/**
 * Covers drag-to-rearrange: setting a book's full page order. The save stub emulates JPA's merge
 * (it writes the saved copy's page number back onto the managed instance), which is the behaviour
 * that once made a page swap give two pages the same number.
 */
class BookGenerationServiceReorderTest {

    private val pageRepository = mock<PageRepository>()
    private val bookRepository = mock<BookRepository>()
    private val orderRepository = mock<OrderRepository>()
    private val service = BookGenerationService(
        bookRepository, orderRepository, mock<PhotoRepository>(), pageRepository, mock<UserRepository>(), mock<TripRepository>(),
        mock<PhotoAnalysisService>(), mock<PhotoSelector>(), mock<LayoutEngine>(), mock<TripService>(),
        mock<BookPlanExplainer>(), mock<EmailService>(), mock<StorageService>(), mock<BookGenerationProcessor>(), false
    )

    private val userId = UUID.randomUUID()
    private val user = User(id = userId, googleSub = "g", email = "a@b.com", name = "A")
    private val trip = Trip(name = "Bali", status = TripStatus.READY_FOR_BOOK_GENERATION).copy(id = UUID.randomUUID(), user = user)
    private val book = Book(id = UUID.randomUUID(), trip = trip, version = 1, title = "Bali", status = BookStatus.PDF_READY)

    /** The "managed" instances, keyed by id, mutated by the save stub like a real persistence context. */
    private val managed = linkedMapOf<UUID, Page>()
    private val pageNumberField = Page::class.java.getDeclaredField("pageNumber").apply { isAccessible = true }

    private fun slot() = PhotoSlot(photoId = UUID.randomUUID(), position = Position(0.0, 0.0), size = Size(1.0, 1.0), offsetX = 0.3, offsetY = 0.7)

    private fun addPages(count: Int, owner: Book = book): List<UUID> = (1..count).map { n ->
        val id = UUID.randomUUID()
        val page = Page(id = id, book = owner, pageNumber = n, layout = Layout.SINGLE_FULL, slots = listOf(slot()))
        managed[id] = page
        whenever(pageRepository.findByIdForUpdate(id)).thenReturn(Optional.of(page))
        id
    }

    private fun numberOf(id: UUID) = managed.getValue(id).pageNumber

    @BeforeEach
    fun stubs() {
        whenever(bookRepository.findByIdAndTripUserId(book.id!!, userId)).thenReturn(Optional.of(book))
        whenever(bookRepository.save(any<Book>())).thenAnswer { it.arguments[0] as Book }
        whenever(pageRepository.save(any<Page>())).thenAnswer {
            val saved = it.arguments[0] as Page
            pageNumberField.setInt(managed.getValue(saved.id!!), saved.pageNumber)
            saved
        }
        whenever(pageRepository.countByBookId(book.id!!)).thenAnswer { managed.values.count { it.book?.id == book.id }.toLong() }
    }

    @Test
    fun `moving a spread renumbers pages by their position in the request`() {
        val ids = addPages(7) // 1 | 2-3 | 4-5 | 6 | 7
        val moved = listOf(ids[0], ids[3], ids[4], ids[1], ids[2], ids[5], ids[6]) // spread 4-5 before 2-3

        service.reorderPages(book.id!!, moved, userId)

        moved.forEachIndexed { index, id -> assertEquals(index + 1, numberOf(id)) }
        assertEquals(managed.size, managed.values.map { it.pageNumber }.toSet().size) // all unique
        assertEquals(0.3, managed.getValue(ids[3]).slots[0].offsetX) // content and framing untouched
        assertEquals(BookStatus.READY_FOR_PREVIEW, book.status) // exported PDF invalidated
    }

    @Test
    fun `reordering and then reordering back restores the original order`() {
        val ids = addPages(6)
        val moved = listOf(ids[0], ids[3], ids[4], ids[1], ids[2], ids[5])

        service.reorderPages(book.id!!, moved, userId)
        service.reorderPages(book.id!!, ids, userId)

        ids.forEachIndexed { index, id -> assertEquals(index + 1, numberOf(id)) }
    }

    @Test
    fun `sending the current order changes nothing`() {
        val ids = addPages(4)
        service.reorderPages(book.id!!, ids, userId)
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `an order that leaves out a page is rejected`() {
        val ids = addPages(5)
        assertThrows(IllegalArgumentException::class.java) { service.reorderPages(book.id!!, ids.dropLast(1), userId) }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `an order that repeats a page is rejected`() {
        val ids = addPages(3)
        assertThrows(IllegalArgumentException::class.java) { service.reorderPages(book.id!!, listOf(ids[0], ids[1], ids[1]), userId) }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `an order including another book's page is rejected`() {
        val ids = addPages(3)
        val otherBook = Book(id = UUID.randomUUID(), trip = trip, version = 1, title = "Other", status = BookStatus.READY_FOR_PREVIEW)
        val foreign = addPages(1, otherBook)
        assertThrows(IllegalArgumentException::class.java) {
            service.reorderPages(book.id!!, listOf(ids[0], ids[1], foreign[0]), userId)
        }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `another user's book is not found`() {
        val ids = addPages(3)
        assertThrows(BookNotFoundException::class.java) { service.reorderPages(book.id!!, ids, UUID.randomUUID()) }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `a paid book can't be reordered`() {
        val ids = addPages(3)
        val paid = mock<Order>()
        whenever(paid.status).thenReturn("PAID")
        whenever(orderRepository.findFirstByTripIdOrderByCreatedAtDesc(trip.id!!)).thenReturn(paid)
        assertThrows(BookLockedException::class.java) { service.reorderPages(book.id!!, ids.reversed(), userId) }
        verify(pageRepository, never()).save(any<Page>())
    }
}
