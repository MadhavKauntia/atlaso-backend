package com.atlaso.service

import com.atlaso.application.layout.BookPlanExplainer
import com.atlaso.application.layout.PhotoSelector
import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Covers the durable per-trip free-preview reservation: consume once per trip, held across
 * retries/regeneration, double-submit returns the in-flight book, and failure never refunds.
 */
class BookGenerationServiceQuotaTest {

    private val bookRepository = mock<BookRepository>()
    private val userRepository = mock<UserRepository>()
    private val tripRepository = mock<TripRepository>()
    private val tripService = mock<TripService>()
    private val processor = mock<BookGenerationProcessor>()
    private val service = BookGenerationService(
        bookRepository, mock<OrderRepository>(), mock<PhotoRepository>(), mock<PageRepository>(), userRepository, tripRepository,
        mock<PhotoAnalysisService>(), mock<PhotoSelector>(), mock<LayoutEngine>(), tripService,
        mock<BookPlanExplainer>(), mock<EmailService>(), mock<StorageService>(), processor, false
    )

    private val userId = UUID.randomUUID()
    private val tripId = UUID.randomUUID()
    private val user = User(id = userId, googleSub = "g", email = "a@b.com", name = "A")

    private fun trip(chargedAt: Instant? = null) =
        Trip(name = "Bali", status = TripStatus.READY_FOR_BOOK_GENERATION).copy(id = tripId, user = user, previewChargedAt = chargedAt)

    private fun book(version: Int, t: Trip, status: BookStatus = BookStatus.GENERATING) =
        Book(id = UUID.randomUUID(), trip = t, version = version, title = "Bali", status = status)

    @Test
    fun `first generation reserves a preview and records it durably on the trip`() {
        val t = trip(chargedAt = null)
        whenever(tripService.getTrip(tripId, userId)).thenReturn(t)
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(t))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList())
        whenever(userRepository.tryConsumeFreePreview(userId)).thenReturn(1)
        whenever(bookRepository.save(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }

        service.startGeneration(tripId, userId)

        verify(userRepository).tryConsumeFreePreview(userId)
        verify(bookRepository).save(any<Book>())
        assertNotNull(t.previewChargedAt) { "the trip's reservation should be stamped" }
    }

    @Test
    fun `first generation with an exhausted quota is blocked and never starts the worker`() {
        val t = trip(chargedAt = null)
        whenever(tripService.getTrip(tripId, userId)).thenReturn(t)
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(t))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(emptyList())
        whenever(userRepository.tryConsumeFreePreview(userId)).thenReturn(0)

        assertThrows(FreePreviewQuotaExceededException::class.java) { service.startGeneration(tripId, userId) }
        verify(bookRepository, never()).save(any<Book>())
        verify(processor, never()).process(any(), any())
    }

    @Test
    fun `retrying after a failed first generation does not charge again (reservation is held)`() {
        val t = trip(chargedAt = Instant.now()) // already reserved by the failed attempt
        whenever(tripService.getTrip(tripId, userId)).thenReturn(t)
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(t))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(listOf(book(1, t, BookStatus.FAILED)))
        whenever(bookRepository.save(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }

        service.startGeneration(tripId, userId)

        verify(userRepository, never()).tryConsumeFreePreview(any())
    }

    @Test
    fun `a rapid double-submit returns the in-flight book without charging or starting a worker`() {
        val t = trip(chargedAt = Instant.now())
        val inFlight = book(1, t, BookStatus.GENERATING)
        whenever(tripService.getTrip(tripId, userId)).thenReturn(t)
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(t))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(listOf(inFlight))

        val result = service.startGeneration(tripId, userId)

        assertEquals(inFlight.id, result.id)
        verify(userRepository, never()).tryConsumeFreePreview(any())
        verify(bookRepository, never()).save(any<Book>())
        verify(processor, never()).process(any(), any())
    }

    @Test
    fun `regeneration does not consume a preview`() {
        val t = trip(chargedAt = Instant.now())
        val existing = book(1, t, BookStatus.READY_FOR_PREVIEW)
        whenever(bookRepository.findByIdAndTripUserId(existing.id!!, userId)).thenReturn(Optional.of(existing))
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(t))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(listOf(existing))
        whenever(bookRepository.save(any<Book>())).thenAnswer { (it.arguments[0] as Book).copy(id = UUID.randomUUID()) }

        service.startRegeneration(existing.id!!, userId)

        verify(userRepository, never()).tryConsumeFreePreview(any())
    }

    @Test
    fun `regeneration during an in-flight generation returns that book and starts no worker`() {
        val t = trip(chargedAt = Instant.now())
        val inFlight = book(1, t, BookStatus.GENERATING)
        whenever(bookRepository.findByIdAndTripUserId(inFlight.id!!, userId)).thenReturn(Optional.of(inFlight))
        whenever(tripRepository.findByIdForUpdate(tripId)).thenReturn(Optional.of(t))
        whenever(bookRepository.findByTripIdOrderByVersionDesc(tripId)).thenReturn(listOf(inFlight))

        val result = service.startRegeneration(inFlight.id!!, userId)

        assertEquals(inFlight.id, result.id)
        verify(bookRepository, never()).save(any<Book>())
        verify(processor, never()).process(any(), any())
        verify(userRepository, never()).tryConsumeFreePreview(any())
    }

    @Test
    fun `markFailed flips status once and never touches the quota (no refund)`() {
        val v1 = book(1, trip(chargedAt = Instant.now()), BookStatus.GENERATING)
        whenever(bookRepository.markFailedIfGenerating(v1.id!!, BookStatus.GENERATING, BookStatus.FAILED)).thenReturn(1)

        service.markFailed(v1.id!!)

        verify(bookRepository).markFailedIfGenerating(v1.id!!, BookStatus.GENERATING, BookStatus.FAILED)
        verifyNoInteractions(userRepository)
    }
}
