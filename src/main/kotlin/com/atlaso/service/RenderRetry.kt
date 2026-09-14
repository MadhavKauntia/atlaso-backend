package com.atlaso.service

/**
 * Runs [block], retrying on any exception up to [attempts] times with linear backoff
 * (backoffMs, 2·backoffMs, …). Returns the result, or null if every attempt failed.
 *
 * Used to harden PDF export against transient image load/decode failures (a flaky S3
 * read or a low-memory ImageIO hiccup) so a single blip no longer blanks a photo slot.
 */
fun <T> retryOrNull(
    attempts: Int,
    backoffMs: Long,
    onError: (attempt: Int, e: Exception) -> Unit = { _, _ -> },
    block: () -> T
): T? {
    for (attempt in 1..attempts) {
        try {
            return block()
        } catch (e: Exception) {
            onError(attempt, e)
            if (attempt < attempts && backoffMs > 0) Thread.sleep(backoffMs * attempt)
        }
    }
    return null
}
