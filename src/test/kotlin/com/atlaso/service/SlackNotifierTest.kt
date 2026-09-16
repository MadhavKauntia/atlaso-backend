package com.atlaso.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SlackNotifierTest {

    @Test
    fun `flags team accounts as internal, case- and space-insensitively`() {
        assertTrue(SlackNotifier.isInternalEmail("mkauntia@gmail.com"))
        assertTrue(SlackNotifier.isInternalEmail("  MKauntia@Gmail.com  "))
        assertTrue(SlackNotifier.isInternalEmail("sana.agg@gmail.com"))
    }

    @Test
    fun `treats a real user as external`() {
        assertFalse(SlackNotifier.isInternalEmail("someone@example.com"))
    }

    @Test
    fun `formats whole rupees without decimals and part-rupees with two`() {
        assertEquals("₹1,999", SlackNotifier.formatRupees(199900))
        assertEquals("₹999.50", SlackNotifier.formatRupees(99950))
        assertEquals("₹0", SlackNotifier.formatRupees(0))
    }
}
