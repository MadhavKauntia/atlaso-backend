package com.atlaso.service

import com.atlaso.application.layout.BookPlanExplainer
import com.atlaso.application.layout.PhotoSelector
import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.domain.user.FREE_PREVIEW_QUOTA
import com.atlaso.domain.user.User
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PageRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

/** Covers the free-preview quota: consume on first generation, free on regeneration, refund on failure. */
class BookGenerationServiceQuotaTest {

    private val bookRepository = mock<BookRepository>()
    private val userRepository = mock<UserRepository>()
    private val tripService = mock<TripService>()
    private val processor = mock<BookGenerationProcessor>()
    private val service = BookGenerationService(
        bookRepository, mock<PhotoRepository>(), mock<PageRepository>(), userRepository,
        mock<PhotoAnalysisService>(), mock<PhotoSelector>(), mock<LayoutEngine>(), tripService,
        mock<BookPlanExplainer>(), mock<EmailService>(), mock<StorageService>(), processor, false
    )

    private val userId = UUID.randomUUID()
    private val tripId = UUID.randomUUID()
    private val user = User(id = userId, googleSub = "g", email = "a@b.com", name = "A")
    private val trip = Trip(name = "Bali", status = TripStatus.READY_FOR_BOOK_GENERATION)
        .copy(id = tripId, user = user)

    @Test
    fun `first generation with an exhausted quota is blocked and creates no book`() {
        whenever(tripService.getTrip(tripId, userId)).thenReturn(trip)
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList())
        whenever(userRepository.tryConsumeFreePreview(userId)).thenReturn(0) // none left

        assertThrows(FreePreviewQuotaExceededException::class.java) {
            service.startGeneration(tripId, userId)
        }
        verify(bookRepository, never()).save(any())
        verify(processor, never()).process(any(), any())
    }

    @Test
    fun `first generation consumes one preview and proceeds when quota remains`() {
        whenever(tripService.getTrip(tripId, userId)).thenReturn(trip)
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList())
        whenever(userRepository.tryConsumeFreePreview(userId)).thenReturn(1) // consumed
        whenever(bookRepository.save(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }

        service.startGeneration(tripId, userId)

        verify(userRepository).tryConsumeFreePreview(userId)
        verify(bookRepository).save(any())
    }

    @Test
    fun `regeneration does not consume a preview`() {
        val existing = Book(id = UUID.randomUUID(), trip = trip, version = 1, title = "Bali")
        whenever(bookRepository.findByIdAndTripUserId(existing.id!!, userId)).thenReturn(Optional.of(existing))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(listOf(existing))
        whenever(bookRepository.save(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }

        service.startRegeneration(existing.id!!, userId)

        verify(userRepository, never()).tryConsumeFreePreview(any())
    }

    @Test
    fun `a failed first generation refunds the preview, a failed regeneration does not`() {
        val v1 = Book(id = UUID.randomUUID(), trip = trip, version = 1, title = "Bali", status = BookStatus.GENERATING)
        whenever(bookRepository.findById(v1.id!!)).thenReturn(Optional.of(v1))
        service.markFailed(v1.id!!)
        verify(userRepository).refundFreePreview(userId, FREE_PREVIEW_QUOTA)

        val v2 = Book(id = UUID.randomUUID(), trip = trip, version = 2, title = "Bali", status = BookStatus.GENERATING)
        whenever(bookRepository.findById(v2.id!!)).thenReturn(Optional.of(v2))
        service.markFailed(v2.id!!)
        verify(userRepository, times(1)).refundFreePreview(userId, FREE_PREVIEW_QUOTA) // v2 added none; still the single v1 refund
    }
}
