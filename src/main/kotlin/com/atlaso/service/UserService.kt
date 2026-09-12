package com.atlaso.service

import com.atlaso.domain.user.User
import com.atlaso.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class UserService(private val userRepository: UserRepository) {

    fun getById(id: UUID): User =
        userRepository.findById(id).orElseThrow { RuntimeException("User not found") }

    fun findOrCreate(googleSub: String, email: String, name: String, pictureUrl: String?): User {
        val existing = userRepository.findByGoogleSub(googleSub)
        if (existing != null) {
            existing.name = name
            existing.pictureUrl = pictureUrl
            return userRepository.save(existing)
        }
        return userRepository.save(User(googleSub = googleSub, email = email, name = name, pictureUrl = pictureUrl))
    }
}
