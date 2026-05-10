package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

data class PhotoGroup(
    val photos: List<Photo>,
    val representativeTime: Instant?
)

@Component
class PhotoGrouper {

    private val logger = LoggerFactory.getLogger(PhotoGrouper::class.java)

    companion object {
        const val TARGET_PAGE_COUNT = 24
    }

    /**
     * Divides photos into exactly [targetPages] chronological groups (or fewer if there are
     * fewer photos than pages). Photos are sorted by capture time, then divided into equal-ish
     * chunks. Within each chunk, photos are ordered by aesthetic score so the LayoutEngine can
     * assign the best featured slot correctly.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val n = sorted.size
        val actualPages = minOf(n, targetPages)
        val base = n / actualPages
        val extras = n % actualPages

        val groups = mutableListOf<PhotoGroup>()
        var idx = 0
        for (i in 0 until actualPages) {
            val size = base + if (i < extras) 1 else 0
            val chunk = sorted.subList(idx, idx + size)
            idx += size
            val ordered = chunk.sortedByDescending { it.signals?.aestheticScore ?: 0.0 }
            groups.add(PhotoGroup(photos = ordered, representativeTime = medianTime(chunk)))
        }

        logger.info("Grouped {} photos into {} pages", n, groups.size)
        return groups
    }

    private fun medianTime(photos: List<Photo>): Instant? {
        val times = photos.mapNotNull { it.metadata.takenAt }.sorted()
        if (times.isEmpty()) return null
        return times[times.size / 2]
    }
}
