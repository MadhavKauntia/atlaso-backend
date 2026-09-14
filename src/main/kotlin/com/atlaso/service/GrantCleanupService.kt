package com.atlaso.service

import com.atlaso.repository.UploadGrantRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Periodically removes expired upload grants and their orphaned S3 objects (reservations that
 * were never confirmed), and prunes old consumed grant rows. Without this, abandoned uploads
 * would accumulate storage indefinitely.
 */
@Service
class GrantCleanupService(
    private val uploadGrantRepository: UploadGrantRepository,
    private val storageService: StorageService,
) {
    private val logger = LoggerFactory.getLogger(GrantCleanupService::class.java)

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L) // hourly, 5 min after start
    @Transactional
    fun cleanupExpiredGrants() {
        val cutoff = Instant.now().minus(PhotoUploadService.GRANT_TTL)

        // Abandoned reservations: delete the orphaned S3 objects (best-effort) then the rows.
        val expired = uploadGrantRepository.findByConsumedFalseAndCreatedAtBefore(cutoff)
        expired.forEach { g ->
            runCatching { storageService.delete(g.storageKey) }
            g.thumbnailKey?.let { runCatching { storageService.delete(it) } }
        }
        if (expired.isNotEmpty()) uploadGrantRepository.deleteAll(expired)

        // Consumed grants are done once the photo exists — prune the old rows (objects are kept).
        val prunedConsumed = uploadGrantRepository.deleteByConsumedTrueAndCreatedAtBefore(cutoff)

        if (expired.isNotEmpty() || prunedConsumed > 0) {
            logger.info(
                "Grant cleanup: removed {} expired reservations (+objects), pruned {} consumed rows",
                expired.size, prunedConsumed
            )
        }
    }
}
