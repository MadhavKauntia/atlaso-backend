package com.atlaso.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Runs book generation off the request thread. Kept separate from BookGenerationService so
 * the @Async proxy is honoured (a self-invoked @Async method would run synchronously).
 */
@Component
class BookGenerationProcessor(
    private val bookGenerationService: BookGenerationService
) {
    private val logger = LoggerFactory.getLogger(BookGenerationProcessor::class.java)

    @Async("bookGenerationExecutor")
    fun process(bookId: UUID, tripId: UUID) {
        try {
            bookGenerationService.runGeneration(bookId, tripId)
            // Post-commit (runGeneration's own transaction has committed): notify the owner so a
            // user who closed the tab still gets the preview link. Never fails the generation.
            runCatching { bookGenerationService.sendBookReadyEmailIfNeeded(bookId) }
                .onFailure { logger.error("Book-ready email step failed for book {}", bookId, it) }
        } catch (e: Exception) {
            logger.error("Async book generation failed for book {}", bookId, e)
            runCatching { bookGenerationService.markFailed(bookId) }
                .onFailure { logger.error("Failed to mark book {} as FAILED", bookId, it) }
        }
    }
}
