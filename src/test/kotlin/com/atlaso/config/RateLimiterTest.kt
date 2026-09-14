package com.atlaso.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RateLimiterTest {

    @Test
    fun `enforces the limit within a window, per key`() {
        val rl = RateLimiter()
        repeat(3) { assertTrue(rl.tryAcquire("user-a", limit = 3, windowSeconds = 3600)) }
        assertFalse(rl.tryAcquire("user-a", limit = 3, windowSeconds = 3600)) // 4th over the limit
        assertTrue(rl.tryAcquire("user-b", limit = 3, windowSeconds = 3600))  // other key independent
    }

    @Test
    fun `map stays bounded under rotating keys`() {
        val rl = RateLimiter()
        // All fresh, unique keys (the case the naive stale-only eviction failed to bound).
        repeat(120_000) { rl.tryAcquire("k$it", limit = 1, windowSeconds = 3600) }
        assertTrue(rl.trackedKeys() <= 50_000) { "rate-limiter map grew unbounded: ${rl.trackedKeys()}" }
    }
}
