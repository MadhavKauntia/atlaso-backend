package com.atlaso.repository

import com.atlaso.domain.book.Page
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PageRepository : JpaRepository<Page, UUID> {
    fun findByBookIdOrderByPageNumberAsc(bookId: UUID): List<Page>
}
