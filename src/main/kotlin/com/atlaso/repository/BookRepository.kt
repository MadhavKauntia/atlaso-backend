package com.atlaso.repository

import com.atlaso.domain.book.Book
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface BookRepository : JpaRepository<Book, UUID> {
    fun findByTripIdOrderByVersionDesc(tripId: UUID): List<Book>
    fun findByIdAndTripUserId(id: UUID, userId: UUID): Optional<Book>
}
