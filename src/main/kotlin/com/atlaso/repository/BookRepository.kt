package com.atlaso.repository

import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface BookRepository : JpaRepository<Book, UUID> {
    fun findByTripIdOrderByVersionDesc(tripId: UUID): List<Book>
    fun findByIdAndTripUserId(id: UUID, userId: UUID): Optional<Book>

    /** True once a trip has any book — used to lock further photo uploads for that trip. */
    fun existsByTripId(tripId: UUID): Boolean

    /**
     * One-time GENERATING -> FAILED transition. Returns 1 only for the call that actually flips the
     * row, so a repeated failure callback can't trigger the refund more than once.
     */
    @Modifying
    @Query("UPDATE Book b SET b.status = :failed WHERE b.id = :id AND b.status = :generating")
    fun markFailedIfGenerating(@Param("id") id: UUID, @Param("generating") generating: BookStatus, @Param("failed") failed: BookStatus): Int
}
