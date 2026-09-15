package com.atlaso.repository

import com.atlaso.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByGoogleSub(googleSub: String): User?

    /**
     * Atomically consumes one free preview: decrements only when some remain. Returns the number of
     * rows updated — 1 if a preview was consumed, 0 if the quota was already exhausted. The WHERE
     * guard makes the check-and-decrement a single race-free statement.
     */
    @Modifying
    @Query("UPDATE User u SET u.freePreviewsRemaining = u.freePreviewsRemaining - 1 WHERE u.id = :id AND u.freePreviewsRemaining > 0")
    fun tryConsumeFreePreview(@Param("id") id: UUID): Int

    /** Resets a user's free-preview quota to [quota] (called after a paid order). */
    @Modifying
    @Query("UPDATE User u SET u.freePreviewsRemaining = :quota WHERE u.id = :id")
    fun resetFreePreviews(@Param("id") id: UUID, @Param("quota") quota: Int): Int
}
