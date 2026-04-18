package com.atlaso.repository

import com.atlaso.domain.book.Book
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BookRepository : JpaRepository<Book, UUID> {
    fun findByTripIdOrderByVersionDesc(tripId: UUID): List<Book>
}
