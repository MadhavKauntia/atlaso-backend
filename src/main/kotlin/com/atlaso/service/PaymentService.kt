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

    data class RazorpayPayment(
        val method: String?,
        val amountMinor: Long?,
        val email: String?,
        val contact: String?,
    )

    /**
     * Fetches a captured payment from Razorpay (for the payment method, amount and
     * payer details shown on the receipt). Best-effort — returns null on failure.
     */
    fun fetchPayment(paymentId: String): RazorpayPayment? {
        return try {
            val request = Request.Builder()
                .url("https://api.razorpay.com/v1/payments/$paymentId")
                .header("Authorization", Credentials.basic(keyId, keySecret))
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Razorpay payment fetch failed: HTTP {}", response.code)
                    return null
                }
                val n = objectMapper.readTree(response.body?.string().orEmpty())
                RazorpayPayment(
                    method = n.get("method")?.asText()?.takeIf { it.isNotBlank() },
                    amountMinor = n.get("amount")?.asLong(),
                    email = n.get("email")?.asText()?.takeIf { it.isNotBlank() && it != "null" },
                    contact = n.get("contact")?.asText()?.takeIf { it.isNotBlank() && it != "null" },
                )
            }
        } catch (ex: Exception) {
            logger.warn("Razorpay payment fetch error for {}: {}", paymentId, ex.message)
            null
        }
    }

    /**
     * Creates a Razorpay order. [amountMinor] is in the smallest currency unit
     * (paise for INR) and must be at least 100.
     */
    fun createOrder(
        amountMinor: Long,
        currency: String,
        receipt: String?,
        offerIds: List<String>? = null,
        forceOffer: Boolean = false,
    ): RazorpayOrder {
        require(amountMinor >= 100) { "amount must be at least 100 (minor units)" }

        // Always send the FULL amount — Razorpay subtracts any linked offer's discount.
        val payload = objectMapper.writeValueAsString(
            buildMap<String, Any> {
                put("amount", amountMinor)
                put("currency", currency)
                put("receipt", receipt ?: "rcpt_${System.currentTimeMillis()}")
                if (!offerIds.isNullOrEmpty()) {
                    put("offers", offerIds)
                    // force_offer requires exactly one offer id in the array.
                    if (forceOffer && offerIds.size == 1) put("force_offer", true)
                }
            }
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
