package com.atlaso.service

import com.atlaso.domain.order.Order
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Posts internal ops alerts to Slack incoming webhooks — a new signup to #signups and a paid
 * order to #orders. Best-effort and fully async (off the request thread), so a Slack failure or
 * latency never affects the user-facing flow. Team accounts are skipped for signups so the
 * channel only reflects real users; orders are never filtered (every paid order is worth seeing).
 * Callers should invoke these AFTER their transaction commits.
 */
@Service
class SlackNotifier(
    @Value("\${slack.signups-webhook-url:}") private val signupsWebhookUrl: String,
    @Value("\${slack.orders-webhook-url:}") private val ordersWebhookUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(SlackNotifier::class.java)
    private val http = OkHttpClient()

    @Async("slackExecutor")
    fun notifySignup(email: String, name: String?) {
        if (isInternalEmail(email)) {
            logger.debug("Skipping Slack signup ping for internal account {}", email)
            return
        }
        val who = name?.takeIf { it.isNotBlank() }?.let { "$it ($email)" } ?: email
        post(signupsWebhookUrl, ":wave: New signup: $who")
    }

    @Async("slackExecutor")
    fun notifyOrder(order: Order) {
        val where = listOfNotNull(
            order.city?.takeIf { it.isNotBlank() },
            order.state?.takeIf { it.isNotBlank() },
        ).joinToString(", ").ifBlank { "—" }
        val who = order.customerName?.takeIf { it.isNotBlank() } ?: order.customerEmail ?: "—"
        val coupon = order.couponCode?.takeIf { it.isNotBlank() }?.let { " · coupon $it" } ?: ""
        post(
            ordersWebhookUrl,
            ":moneybag: New order ATL-${order.number} — ${formatRupees(order.amountMinor)} × ${order.quantity} — $who ($where)$coupon",
        )
    }

    private fun post(webhookUrl: String, text: String) {
        if (webhookUrl.isBlank()) {
            logger.debug("Slack webhook not configured; skipping message: {}", text)
            return
        }
        runCatching {
            val body = objectMapper.writeValueAsString(mapOf("text" to text))
            val request = Request.Builder()
                .url(webhookUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Slack post failed: HTTP {} — {}", response.code, response.body?.string().orEmpty())
                }
            }
        }.onFailure { logger.warn("Slack post error: {}", it.message) }
    }

    companion object {
        // Team accounts (see internal-accounts memory) — excluded from signup pings.
        private val INTERNAL_EMAILS = setOf("mkauntia@gmail.com", "sana.agg@gmail.com")

        fun isInternalEmail(email: String): Boolean = email.trim().lowercase() in INTERNAL_EMAILS

        /** Paise → a ₹ string, dropping the decimals when the amount is whole rupees. */
        fun formatRupees(minor: Long): String =
            if (minor % 100 == 0L) "₹%,d".format(minor / 100) else "₹%,.2f".format(minor / 100.0)
    }
}
