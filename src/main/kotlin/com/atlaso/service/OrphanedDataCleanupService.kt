package com.atlaso.service

import com.atlaso.config.CleanupProperties
import com.atlaso.repository.BookRepository
import com.atlaso.repository.OrderRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UploadGrantRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Reclaims storage and rows left behind by abandoned guest flows:
 *   - a guest uploads photos but never signs in to continue (unclaimed trip WITH photos), and
 *   - a guest starts a book but never uploads (unclaimed trip with NO photos).
 *
 * Only ever touches UNCLAIMED trips (`user IS NULL`). A trip is skipped if it has any book or order,
 * so nothing with generation history or customer value is removed. S3 objects are deleted before the
 * DB rows; if any object delete fails the trip is held for the next run rather than orphaning it.
 *
 * When [CleanupProperties.enabled] is false the run is a no-op that only logs what it would delete.
 */
@Service
class OrphanedDataCleanupService(
    private val props: CleanupProperties,
    private val tripRepository: TripRepository,
    private val photoRepository: PhotoRepository,
    private val bookRepository: BookRepository,
    private val orderRepository: OrderRepository,
    private val uploadGrantRepository: UploadGrantRepository,
    private val purger: TripStoragePurger,
) {
    private val logger = LoggerFactory.getLogger(OrphanedDataCleanupService::class.java)

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L) // hourly, 10 min after start
    @Transactional
    fun cleanupOrphanedTrips() {
        val now = Instant.now()
        val emptyCutoff = now.minus(Duration.ofHours(props.emptyTripCutoffHours))
        val photoCutoff = now.minus(Duration.ofHours(props.photoTripCutoffHours))

        // Widest candidate set = unclaimed trips older than the SHORTER (empty) window; each trip's
        // real due-date depends on whether it has photos. Oldest-first, bounded batch.
        val candidates = tripRepository.findByUserIsNullAndCreatedAtBefore(
            emptyCutoff, PageRequest.of(0, props.batchSize, Sort.by("createdAt").ascending())
        )
        // Fall through even when empty so every run logs a summary — a heartbeat for a job that
        // deletes data. An empty run is the common case and just reports candidates=0.

        var deletedTrips = 0
        var deletedObjects = 0
        var wouldDelete = 0
        var protectedSkipped = 0
        var notYetDue = 0
        var retainedForRetry = 0

        for (trip in candidates) {
            val tripId = trip.id ?: continue
            val createdAt = trip.createdAt ?: continue

            // Defensive: an unclaimed trip shouldn't have these, but never delete one that does.
            if (bookRepository.existsByTripId(tripId) || orderRepository.existsByTripId(tripId)) {
                protectedSkipped++
                continue
            }

            val photoCount = photoRepository.countByTripId(tripId)
            // Empty trips are already past emptyCutoff (the query cutoff); trips with photos must also
            // be past the longer photo window before we delete them.
            val due = photoCount == 0L || createdAt.isBefore(photoCutoff)
            if (!due) {
                notYetDue++
                continue
            }

            val keys = purger.keysForTrip(tripId)

            if (!props.enabled) {
                logger.info(
                    "[cleanup dry-run] would delete unclaimed trip {} (created {}, {} photos, {} S3 objects)",
                    tripId, createdAt, photoCount, keys.size
                )
                wouldDelete++
                continue
            }

            // Delete S3 first; only remove DB rows once every object is gone (else retry next run).
            if (!purger.purge(tripId, keys)) {
                retainedForRetry++
                continue
            }
            deletedObjects += keys.size

            photoRepository.deleteByTripId(tripId)
            uploadGrantRepository.deleteByTripId(tripId)
            tripRepository.delete(trip)
            deletedTrips++
        }

        logger.info(
            "Orphaned-trip cleanup (enabled={}): candidates={} deletedTrips={} deletedObjects={} " +
                "wouldDelete={} protectedSkipped={} notYetDue={} retainedForRetry={}",
            props.enabled, candidates.size, deletedTrips, deletedObjects,
            wouldDelete, protectedSkipped, notYetDue, retainedForRetry
        )
    }
}
