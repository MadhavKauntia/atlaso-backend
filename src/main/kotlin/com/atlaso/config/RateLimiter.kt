package com.atlaso.config

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal in-memory fixed-window rate limiter (per single instance — fine for one Railway
 * container). Guards the expensive endpoints (book generation → OpenAI spend, uploads, order
 * creation) against abuse and runaway cost. Not a precise distributed limiter; it's an
 * abuse/cost backstop, not fine-grained QoS.
 */
@Component
class RateLimiter {

    private class Window(var startMs: Long, var count: Int)

    private val buckets = ConcurrentHashMap<String, Window>()

    /** Returns true if this call is within [limit] for the current [windowSeconds] window on [key]. */
    fun tryAcquire(key: String, limit: Int, windowSeconds: Long): Boolean {
        val now = System.currentTimeMillis()
        var allowed = false
        buckets.compute(key) { _, existing ->
            val w = if (existing == null || now - existing.startMs >= windowSeconds * 1000) Window(now, 0) else existing
            w.count += 1
            allowed = w.count <= limit
            w
        }
        return allowed
    }
}
