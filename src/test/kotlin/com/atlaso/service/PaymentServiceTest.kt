package com.atlaso.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PaymentServiceTest {

    private val webhookSecret = "whsec_test_123"
    private val svc = PaymentService(
        keyId = "rzp_test",
        keySecret = "key_secret",
        webhookSecret = webhookSecret,
        objectMapper = ObjectMapper(),
    )

    private fun sign(body: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `accepts a signature computed with the webhook secret`() {
        val body = """{"event":"payment.captured"}"""
        assertTrue(svc.verifyWebhookSignature(body, sign(body, webhookSecret)))
    }

    @Test
    fun `rejects a signature computed with the wrong secret`() {
        val body = """{"event":"payment.captured"}"""
        assertFalse(svc.verifyWebhookSignature(body, sign(body, "wrong_secret")))
    }

    @Test
    fun `rejects a signature for a tampered body`() {
        val signature = sign("""{"amount":100}""", webhookSecret)
        assertFalse(svc.verifyWebhookSignature("""{"amount":999}""", signature))
    }

    @Test
    fun `rejects a missing signature`() {
        assertFalse(svc.verifyWebhookSignature("""{"event":"x"}""", null))
    }

    @Test
    fun `reports webhook configured when a secret is present`() {
        assertTrue(svc.isWebhookConfigured())
    }

    @Test
    fun `reports webhook not configured when the secret is blank`() {
        val unconfigured = PaymentService("rzp_test", "key_secret", "", ObjectMapper())
        assertFalse(unconfigured.isWebhookConfigured())
        assertFalse(unconfigured.verifyWebhookSignature("body", "anything"))
    }
}
