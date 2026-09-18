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
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.domain.user.User
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PageRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

/**
 * Covers drag-to-swap: exchanging two slots' photo content (same page or across pages in the same
 * book), recentring framing, and rejecting cross-book / cross-user / out-of-range swaps.
 */
class BookGenerationServiceSwapTest {

    private val pageRepository = mock<PageRepository>()
    private val service = BookGenerationService(
        mock<BookRepository>(), mock<PhotoRepository>(), pageRepository, mock<UserRepository>(), mock<TripRepository>(),
        mock<PhotoAnalysisService>(), mock<PhotoSelector>(), mock<LayoutEngine>(), mock<TripService>(),
        mock<BookPlanExplainer>(), mock<EmailService>(), mock<StorageService>(), mock<BookGenerationProcessor>(), false
    )

    private val userId = UUID.randomUUID()
    private val user = User(id = userId, googleSub = "g", email = "a@b.com", name = "A")
    private val trip = Trip(name = "Bali", status = TripStatus.READY_FOR_BOOK_GENERATION)
        .copy(id = UUID.randomUUID(), user = user)

    private val pageAId = UUID.randomUUID()
    private val pageBId = UUID.randomUUID()

    @BeforeEach
    fun stubs() {
        whenever(pageRepository.save(any<Page>())).thenAnswer { it.arguments[0] as Page }
    }

    private fun slot(photoId: UUID, rotation: Int = 0, offset: Double? = 0.8) =
        PhotoSlot(photoId = photoId, position = Position(0.0, 0.0), size = Size(1.0, 1.0), rotation = rotation, offsetX = offset, offsetY = offset)

    private fun book(id: UUID = UUID.randomUUID()) =
        Book(id = id, trip = trip, version = 1, title = "Bali", status = BookStatus.READY_FOR_PREVIEW)

    private fun page(id: UUID, book: Book, slots: List<PhotoSlot>): Page {
        val page = Page(id = id, book = book, pageNumber = 2, layout = Layout.TWO_VERTICAL, slots = slots)
        whenever(pageRepository.findByIdForUpdate(id)).thenReturn(Optional.of(page))
        return page
    }

    @Test
    fun `same-page swap exchanges photo content and recentres both frames`() {
        val a0 = UUID.randomUUID(); val a1 = UUID.randomUUID()
        page(pageAId, book(), listOf(slot(a0, rotation = 90), slot(a1, rotation = 0)))

        service.swapSlots(pageAId, 0, pageAId, 1, userId)

        val captor = argumentCaptor<Page>()
        verify(pageRepository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals(listOf(a1, a0), saved.slots.map { it.photoId }) // swapped
        assertEquals(0, saved.slots[0].rotation)   // a1's rotation followed it
        assertEquals(90, saved.slots[1].rotation)  // a0's rotation followed it
        assertNull(saved.slots[0].offsetX)         // framing recentred
        assertNull(saved.slots[1].offsetY)
    }

    @Test
    fun `cross-page swap updates both pages`() {
        val b = book()
        val a0 = UUID.randomUUID(); val b0 = UUID.randomUUID()
        page(pageAId, b, listOf(slot(a0)))
        page(pageBId, b, listOf(slot(b0)))

        service.swapSlots(pageAId, 0, pageBId, 0, userId)

        val captor = argumentCaptor<Page>()
        verify(pageRepository, org.mockito.kotlin.times(2)).save(captor.capture())
        val savedA = captor.allValues.first { it.id == pageAId }
        val savedB = captor.allValues.first { it.id == pageBId }
        assertEquals(b0, savedA.slots[0].photoId)
        assertEquals(a0, savedB.slots[0].photoId)
    }

    @Test
    fun `swap across different books is rejected`() {
        val a0 = UUID.randomUUID(); val b0 = UUID.randomUUID()
        page(pageAId, book(), listOf(slot(a0)))
        page(pageBId, book(), listOf(slot(b0))) // a different book

        assertThrows(PageNotFoundException::class.java) {
            service.swapSlots(pageAId, 0, pageBId, 0, userId)
        }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `swap on another user's book is not found`() {
        val other = User(id = UUID.randomUUID(), googleSub = "g2", email = "c@d.com", name = "C")
        val otherTrip = Trip(name = "X", status = TripStatus.READY_FOR_BOOK_GENERATION).copy(id = UUID.randomUUID(), user = other)
        val otherBook = Book(id = UUID.randomUUID(), trip = otherTrip, version = 1, title = "X", status = BookStatus.READY_FOR_PREVIEW)
        page(pageAId, otherBook, listOf(slot(UUID.randomUUID()), slot(UUID.randomUUID())))

        assertThrows(PageNotFoundException::class.java) {
            service.swapSlots(pageAId, 0, pageAId, 1, userId)
        }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `out-of-range slot index is rejected`() {
        page(pageAId, book(), listOf(slot(UUID.randomUUID())))

        assertThrows(IllegalArgumentException::class.java) {
            service.swapSlots(pageAId, 0, pageAId, 5, userId)
        }
        verify(pageRepository, never()).save(any<Page>())
    }
}
