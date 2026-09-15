package com.atlaso.service

import com.atlaso.repository.UploadGrantRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
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

    private companion object {
        const val BATCH_SIZE = 200 // rows processed per run — bounds memory and object-delete work
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L) // hourly, 5 min after start
    @Transactional
    fun cleanupExpiredGrants() {
        val cutoff = Instant.now().minus(PhotoUploadService.GRANT_TTL)

        // Abandoned reservations: delete the orphaned S3 objects FIRST, and only remove the grant
        // row once every object for it is gone. If any delete fails (transient S3 error), we keep
        // the row so the next run retries it — a deleted row would otherwise orphan the object
        // forever. Bounded batch: whatever we don't reach (or fail) is picked up next run.
        val expired = uploadGrantRepository.findByConsumedFalseAndCreatedAtBefore(
            cutoff, PageRequest.of(0, BATCH_SIZE)
        )
        val deletableIds = mutableListOf<java.util.UUID>()
        var failed = 0
        for (g in expired) {
            val mainOk = runCatching { storageService.delete(g.storageKey) }
                .onFailure { logger.warn("Failed to delete orphaned object {}: {}", g.storageKey, it.message) }
                .isSuccess
            val thumbOk = g.thumbnailKey?.let { tk ->
                runCatching { storageService.delete(tk) }
                    .onFailure { logger.warn("Failed to delete orphaned thumbnail {}: {}", tk, it.message) }
                    .isSuccess
            } ?: true
            if (mainOk && thumbOk) deletableIds.add(g.id!!) else failed++
        }
        val removedReservations = if (deletableIds.isNotEmpty()) uploadGrantRepository.deleteByIdIn(deletableIds) else 0

        // Consumed grants are done once the photo exists — prune the old rows (objects are kept).
        val prunedConsumed = uploadGrantRepository.deleteByConsumedTrueAndCreatedAtBefore(cutoff)

        if (removedReservations > 0 || failed > 0 || prunedConsumed > 0) {
            logger.info(
                "Grant cleanup: removed {} expired reservations (+objects), {} retained for retry, pruned {} consumed rows",
                removedReservations, failed, prunedConsumed
            )
        }
    }
}
