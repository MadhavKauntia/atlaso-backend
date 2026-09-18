package com.atlaso.repository

import com.atlaso.domain.book.Page
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface PageRepository : JpaRepository<Page, UUID> {
    fun findByBookIdOrderByPageNumberAsc(bookId: UUID): List<Page>

    /** Row-locks a page so read-modify-write slot edits (swap/offset/photo) can't lost-update
     *  each other under concurrent fire-and-forget requests. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Page p WHERE p.id = :id")
    fun findByIdForUpdate(id: UUID): Optional<Page>
}
