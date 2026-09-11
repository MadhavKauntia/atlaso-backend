package com.atlaso.service

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Thin integration with Razorpay's REST API. Orders are created by calling
 * https://api.razorpay.com/v1/orders with HTTP Basic auth (keyId:keySecret);
 * payment signatures are verified locally with HMAC-SHA256 so the secret never
 * leaves the backend. No Razorpay SDK is required — we reuse the OkHttp client
 * and Jackson mapper already on the classpath.
 */
@Service
class PaymentService(
    @Value("\${razorpay.key-id}") private val keyId: String,
    @Value("\${razorpay.key-secret}") private val keySecret: String,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)
    private val http = OkHttpClient()

    data class RazorpayOrder(val orderId: String, val amount: Long, val currency: String)

    /**
     * Creates a Razorpay order. [amountMinor] is in the smallest currency unit
     * (paise for INR) and must be at least 100.
     */
    fun createOrder(amountMinor: Long, currency: String, receipt: String?): RazorpayOrder {
        require(amountMinor >= 100) { "amount must be at least 100 (minor units)" }

        val payload = objectMapper.writeValueAsString(
            mapOf(
                "amount" to amountMinor,
                "currency" to currency,
                "receipt" to (receipt ?: "rcpt_${System.currentTimeMillis()}"),
            )
        )

        val request = Request.Builder()
            .url("https://api.razorpay.com/v1/orders")
            .header("Authorization", Credentials.basic(keyId, keySecret))
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) {
                logger.error("Razorpay authentication failed (401)")
                throw RazorpayAuthException("Razorpay authentication failed")
            }
            if (!response.isSuccessful) {
                logger.error("Razorpay order creation failed: HTTP {} {}", response.code, body)
                throw RazorpayException("Razorpay order creation failed (HTTP ${response.code})")
            }
            val node = objectMapper.readTree(body)
            return RazorpayOrder(
                orderId = node.get("id").asText(),
                amount = node.get("amount").asLong(),
                currency = node.get("currency").asText(),
            )
        }
    }

    /**
     * Verifies a payment signature. Razorpay signs `orderId|paymentId` with
     * HMAC-SHA256 keyed by the secret. Comparison is constant-time.
     */
    fun verifySignature(orderId: String, paymentId: String, signature: String): Boolean {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keySecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$orderId|$paymentId".toByteArray(Charsets.UTF_8))
        val expected = digest.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8),
        )
    }
}

class RazorpayException(message: String) : RuntimeException(message)
class RazorpayAuthException(message: String) : RuntimeException(message)
