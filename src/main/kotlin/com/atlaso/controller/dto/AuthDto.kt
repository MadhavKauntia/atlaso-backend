package com.atlaso.controller.dto

import com.atlaso.domain.user.User
import java.util.UUID

data class GoogleAuthRequest(val idToken: String)

data class AuthResponse(val token: String, val user: UserDto)

data class UserDto(
    val id: UUID,
    val email: String,
    val name: String,
    val pictureUrl: String?
) {
    companion object {
        fun from(user: User) = UserDto(
            id = user.id!!,
            email = user.email,
            name = user.name,
            pictureUrl = user.pictureUrl
        )
    }
}
