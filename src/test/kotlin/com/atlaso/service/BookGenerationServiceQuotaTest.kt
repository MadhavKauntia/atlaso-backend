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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional
import java.util.UUID

/** Covers the free-preview quota: consume on first generation, free on regeneration, concurrency, refund. */
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
    fun `first generation with an exhausted quota is blocked and never starts the worker`() {
        whenever(tripService.getTrip(tripId, userId)).thenReturn(trip)
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList())
        whenever(bookRepository.saveAndFlush(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }
        whenever(userRepository.tryConsumeFreePreview(userId)).thenReturn(0) // none left

        assertThrows(FreePreviewQuotaExceededException::class.java) {
            service.startGeneration(tripId, userId)
        }
        verify(processor, never()).process(any(), any()) // the throw rolls back before the worker starts
    }

    @Test
    fun `first generation consumes one preview and proceeds when quota remains`() {
        whenever(tripService.getTrip(tripId, userId)).thenReturn(trip)
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList())
        whenever(bookRepository.saveAndFlush(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }
        whenever(userRepository.tryConsumeFreePreview(userId)).thenReturn(1) // consumed

        service.startGeneration(tripId, userId)

        verify(userRepository).tryConsumeFreePreview(userId)
        verify(bookRepository).saveAndFlush(any<Book>())
    }

    @Test
    fun `a concurrent first-generation loser returns the in-flight book without charging`() {
        val winner = Book(id = UUID.randomUUID(), trip = trip, version = 1, title = "Bali", status = BookStatus.GENERATING)
        whenever(tripService.getTrip(tripId, userId)).thenReturn(trip)
        // First read sees no book (computes v1); after the unique-constraint violation, the winner's book is present.
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList(), listOf(winner))
        whenever(bookRepository.saveAndFlush(any<Book>())).thenThrow(DataIntegrityViolationException("duplicate (trip_id, version)"))

        val result = service.startGeneration(tripId, userId)

        assertEquals(winner.id, result.id)
        verify(userRepository, never()).tryConsumeFreePreview(any()) // loser must not charge
        verify(processor, never()).process(any(), any())             // nor start a second worker
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
    fun `a failed first generation refunds exactly once, even on a repeated failure callback`() {
        val v1 = Book(id = UUID.randomUUID(), trip = trip, version = 1, title = "Bali", status = BookStatus.GENERATING)
        // Only the first callback flips GENERATING -> FAILED (1); the second is a no-op (0).
        whenever(bookRepository.markFailedIfGenerating(v1.id!!, BookStatus.GENERATING, BookStatus.FAILED)).thenReturn(1, 0)
        whenever(bookRepository.findById(v1.id!!)).thenReturn(Optional.of(v1))

        service.markFailed(v1.id!!)
        service.markFailed(v1.id!!)

        verify(userRepository, times(1)).refundFreePreview(userId, FREE_PREVIEW_QUOTA)
    }

    @Test
    fun `a failed regeneration does not refund`() {
        val v2 = Book(id = UUID.randomUUID(), trip = trip, version = 2, title = "Bali", status = BookStatus.GENERATING)
        whenever(bookRepository.markFailedIfGenerating(v2.id!!, BookStatus.GENERATING, BookStatus.FAILED)).thenReturn(1)
        whenever(bookRepository.findById(v2.id!!)).thenReturn(Optional.of(v2))

        service.markFailed(v2.id!!)

        verify(userRepository, never()).refundFreePreview(any(), any())
    }
}
