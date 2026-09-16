package com.atlaso.service

import com.atlaso.domain.user.User
import com.atlaso.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val slackNotifier: SlackNotifier,
) {

    fun getById(id: UUID): User =
        userRepository.findById(id).orElseThrow { RuntimeException("User not found") }

    fun findOrCreate(googleSub: String, email: String, name: String, pictureUrl: String?): User {
        val existing = userRepository.findByGoogleSub(googleSub)
        if (existing != null) {
            existing.name = name
            existing.pictureUrl = pictureUrl
            return userRepository.save(existing)
        }
        val created = userRepository.save(User(googleSub = googleSub, email = email, name = name, pictureUrl = pictureUrl))
        // Ping #signups only once the new user is actually committed.
        afterCommit { slackNotifier.notifySignup(created.email, created.name) }
        return created
    }

    /** Runs [action] after the current transaction commits (or immediately if none is active). */
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action()
            })
        } else {
            action()
        }
    }
}
