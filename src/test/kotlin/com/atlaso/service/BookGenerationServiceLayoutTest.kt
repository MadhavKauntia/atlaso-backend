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
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
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
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Covers switching a page's layout: shrink keeps the first N photos, grow fills the extra slots
 * from the trip's unused pool (and blocks when it can't), same-count keeps every photo, and every
 * switch recentres framing. Geometry is stubbed — [LayoutEngine.getSlotGeometry] has its own tests.
 */
class BookGenerationServiceLayoutTest {

    private val bookRepository = mock<BookRepository>()
    private val photoRepository = mock<PhotoRepository>()
    private val pageRepository = mock<PageRepository>()
    private val tripRepository = mock<TripRepository>()
    private val layoutEngine = mock<LayoutEngine>()
    private val service = BookGenerationService(
        bookRepository, photoRepository, pageRepository, mock<UserRepository>(), tripRepository,
        mock<PhotoAnalysisService>(), mock<PhotoSelector>(), layoutEngine, mock<TripService>(),
        mock<BookPlanExplainer>(), mock<EmailService>(), mock<StorageService>(), mock<BookGenerationProcessor>(), false
    )

    private val userId = UUID.randomUUID()
    private val tripId = UUID.randomUUID()
    private val bookId = UUID.randomUUID()
    private val pageId = UUID.randomUUID()
    private val user = User(id = userId, googleSub = "g", email = "a@b.com", name = "A")
    private val trip = Trip(name = "Bali", status = TripStatus.READY_FOR_BOOK_GENERATION)
        .copy(id = tripId, user = user)

    private val base = Instant.parse("2025-10-12T09:00:00Z")

    @BeforeEach
    fun stubs() {
        // Geometry is irrelevant to what these tests assert (photo set + framing), so return a fixed rect.
        whenever(layoutEngine.getSlotGeometry(any(), any(), any()))
            .thenReturn(Position(0.0, 0.0) to Size(1.0, 1.0))
        whenever(pageRepository.save(any<Page>())).thenAnswer { it.arguments[0] as Page }
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(trip))
    }

    private fun photo(secondsAfterBase: Long, rotation: Int = 0): Photo {
        val id = UUID.randomUUID()
        return Photo(
            id = id,
            trip = trip,
            storageKey = "k/$id",
            originalFilename = "$id.jpg",
            contentType = "image/jpeg",
            fileSize = 1_000,
            metadata = PhotoMetadata(width = 4000, height = 3000, takenAt = base, orientation = 1),
            uploadedAt = base.plusSeconds(secondsAfterBase),
            rotation = rotation
        )
    }

    private fun slot(photoId: UUID, offset: Double? = null) =
        PhotoSlot(photoId = photoId, position = Position(0.0, 0.0), size = Size(1.0, 1.0), offsetX = offset, offsetY = offset)

    /** Wires a page (owned by [user]) carrying [slots]+[layout] into the repos and returns it. */
    private fun givenPage(slots: List<PhotoSlot>, layout: Layout): Page {
        val book = Book(id = bookId, trip = trip, version = 1, title = "Bali", status = BookStatus.READY_FOR_PREVIEW)
        val page = Page(id = pageId, book = book, pageNumber = 2, layout = layout, slots = slots)
        book.addPage(page)
        whenever(pageRepository.findByIdForUpdate(pageId)).thenReturn(Optional.of(page))
        whenever(bookRepository.findByIdAndTripUserId(bookId, userId)).thenReturn(Optional.of(book))
        return page
    }

    private fun savedPage(): Page {
        val captor = argumentCaptor<Page>()
        verify(pageRepository).save(captor.capture())
        return captor.firstValue
    }

    @Test
    fun `growing pulls unused photos from the pool in upload order, skipping ones already in the book`() {
        val a = photo(0)
        givenPage(listOf(slot(a.id!!)), Layout.SINGLE_FULL)
        // b/c/d/e are free; a is already placed. Returned deliberately out of order to prove the sort.
        val b = photo(30); val c = photo(10); val d = photo(20); val e = photo(40)
        whenever(photoRepository.findByTripId(tripId)).thenReturn(listOf(e, a, c, b, d))

        service.changePageLayout(pageId, Layout.FOUR_GRID, userId)

        val saved = savedPage()
        assertEquals(Layout.FOUR_GRID, saved.layout)
        // a retained first, then the three earliest *unused* photos by uploadedAt: c(10), d(20), b(30).
        assertEquals(listOf(a.id, c.id, d.id, b.id), saved.slots.map { it.photoId })
        // Growth takes the trip's pessimistic lock before choosing fillers (dedup vs concurrent grows).
        verify(tripRepository).findByIdForUpdate(tripId)
    }

    @Test
    fun `growing beyond the unused pool is rejected`() {
        val a = photo(0)
        givenPage(listOf(slot(a.id!!)), Layout.SINGLE_FULL)
        // Only one spare photo, but FOUR_GRID needs three more.
        whenever(photoRepository.findByTripId(tripId)).thenReturn(listOf(a, photo(10)))

        assertThrows(InsufficientPhotosException::class.java) {
            service.changePageLayout(pageId, Layout.FOUR_GRID, userId)
        }
        verify(pageRepository, never()).save(any<Page>())
    }

    @Test
    fun `filler photos take their own intrinsic rotation`() {
        val a = photo(0)
        givenPage(listOf(slot(a.id!!)), Layout.SINGLE_FULL)
        val rotated = photo(10, rotation = 90)
        whenever(photoRepository.findByTripId(tripId)).thenReturn(listOf(a, rotated))

        service.changePageLayout(pageId, Layout.TWO_VERTICAL, userId)

        val saved = savedPage()
        assertEquals(listOf(a.id, rotated.id), saved.slots.map { it.photoId })
        assertEquals(90, saved.slots[1].rotation)
    }

    @Test
    fun `shrinking keeps the first N photos and never touches the photo pool`() {
        val ids = List(4) { photo(it.toLong()).id!! }
        givenPage(ids.map { slot(it) }, Layout.FOUR_GRID)

        service.changePageLayout(pageId, Layout.SINGLE_FULL, userId)

        val saved = savedPage()
        assertEquals(Layout.SINGLE_FULL, saved.layout)
        assertEquals(listOf(ids[0]), saved.slots.map { it.photoId })
        verify(photoRepository, never()).findByTripId(any())
        // No pool read means no need to lock the trip either.
        verify(tripRepository, never()).findByIdForUpdate(any())
    }

    @Test
    fun `switching recentres framing on every retained slot`() {
        val a = photo(0)
        givenPage(listOf(slot(a.id!!, offset = 0.8)), Layout.SINGLE_FULL)

        service.changePageLayout(pageId, Layout.SINGLE_FRAMED, userId)

        val saved = savedPage()
        assertEquals(Layout.SINGLE_FRAMED, saved.layout)
        assertEquals(a.id, saved.slots[0].photoId)
        assertNull(saved.slots[0].offsetX)
        assertNull(saved.slots[0].offsetY)
    }

    @Test
    fun `a double-page spread cannot be applied to a single page`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.changePageLayout(pageId, Layout.DOUBLE_PAGE_FULL_BLEED, userId)
        }
    }

    @Test
    fun `another user's page is not found (404, not 500)`() {
        val a = photo(0)
        // Page owned by a different user.
        val otherUser = User(id = UUID.randomUUID(), googleSub = "g2", email = "c@d.com", name = "C")
        val otherTrip = Trip(name = "X", status = TripStatus.READY_FOR_BOOK_GENERATION).copy(id = UUID.randomUUID(), user = otherUser)
        val book = Book(id = bookId, trip = otherTrip, version = 1, title = "X", status = BookStatus.READY_FOR_PREVIEW)
        val page = Page(id = pageId, book = book, pageNumber = 2, layout = Layout.SINGLE_FULL, slots = listOf(slot(a.id!!)))
        book.addPage(page)
        whenever(pageRepository.findByIdForUpdate(pageId)).thenReturn(Optional.of(page))

        assertThrows(PageNotFoundException::class.java) {
            service.changePageLayout(pageId, Layout.SINGLE_FRAMED, userId)
        }
        verify(pageRepository, never()).save(any<Page>())
    }
}
