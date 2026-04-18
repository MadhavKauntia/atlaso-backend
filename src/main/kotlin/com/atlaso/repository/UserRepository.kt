package com.atlaso.repository

import com.atlaso.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByGoogleSub(googleSub: String): User?
}
