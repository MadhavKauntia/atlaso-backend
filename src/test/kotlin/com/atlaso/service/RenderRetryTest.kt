package com.atlaso.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RenderRetryTest {

    @Test
    fun `returns the result on first success without retrying`() {
        var calls = 0
        val result = retryOrNull(attempts = 3, backoffMs = 0) { calls++; "ok" }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries a transient failure and then succeeds`() {
        var calls = 0
        val errors = mutableListOf<Int>()
        val result = retryOrNull(attempts = 3, backoffMs = 0, onError = { attempt, _ -> errors.add(attempt) }) {
            calls++
            if (calls < 2) throw RuntimeException("transient blip") // fail once, then succeed
            "loaded"
        }
        assertEquals("loaded", result)
        assertEquals(2, calls)
        assertEquals(listOf(1), errors) // one failure reported, on attempt 1
    }

    @Test
    fun `returns null after exhausting all attempts`() {
        var calls = 0
        val result = retryOrNull<ByteArray>(attempts = 3, backoffMs = 0) { calls++; throw RuntimeException("still failing") }
        assertNull(result)
        assertEquals(3, calls) // tried exactly `attempts` times
    }
}
