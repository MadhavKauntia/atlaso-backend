package com.atlaso.service

import com.atlaso.domain.order.Order
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.Base64
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * Sends transactional email via Brevo's REST API
 * (POST https://api.brevo.com/v3/smtp/email, authenticated with the `api-key`
 * header). No SDK — reuses the OkHttp client and Jackson mapper on the classpath.
 * All sends are best-effort; failures are logged, never thrown.
 */
@Service
class EmailService(
    @Value("\${brevo.api-key}") private val apiKey: String,
    @Value("\${brevo.sender-email}") private val senderEmail: String,
    @Value("\${brevo.sender-name}") private val senderName: String,
    // Feature flag so book-ready emails can be switched off instantly via env.
    @Value("\${atlaso.email.book-ready.enabled:true}") private val bookReadyEmailEnabled: Boolean,
    @Value("\${atlaso.app-url:https://myatlaso.com}") private val appUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(EmailService::class.java)
    private val http = OkHttpClient()

    /**
     * Emails the trip owner that their photobook is ready to preview, with a deep link to the
     * preview page. Best-effort: logs and returns on any problem, never throws.
     */
    fun sendBookReadyEmail(toEmail: String, toName: String?, tripId: UUID, bookId: UUID, bookTitle: String, coverPhotoId: UUID? = null) {
        if (!bookReadyEmailEnabled) {
            logger.info("Book-ready email disabled — skipping for book {}", bookId)
            return
        }
        if (apiKey.isBlank()) {
            logger.warn("Brevo API key not configured — skipping book-ready email for book {}", bookId)
            return
        }
        val previewUrl = "$appUrl/trips/$tripId/preview?bookId=$bookId"
        // Route the cover through the public, self-refreshing image endpoint (302 -> fresh presigned
        // S3 URL on every load) so it still renders when the email is opened days later — never embed
        // a raw presigned URL, which expires in an hour.
        val coverUrl = coverPhotoId?.let { "$appUrl/api/trips/$tripId/photos/$it/image" }
        val body = mapOf(
            "sender" to mapOf("name" to senderName, "email" to senderEmail),
            "to" to listOf(mapOf("email" to toEmail, "name" to (toName ?: toEmail))),
            "subject" to "Your Atlaso photobook is ready to preview",
            "htmlContent" to buildBookReadyHtml(toName, bookTitle, previewUrl, coverUrl),
        )
        try {
            val request = Request.Builder()
                .url("https://api.brevo.com/v3/smtp/email")
                .header("api-key", apiKey)
                .header("accept", "application/json")
                .post(objectMapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    logger.info("Sent book-ready email for book {} to {}", bookId, toEmail)
                } else {
                    logger.error(
                        "Brevo book-ready email failed for book {}: HTTP {} {}",
                        bookId, response.code, response.body?.string().orEmpty()
                    )
                }
            }
        } catch (ex: Exception) {
            logger.error("Brevo book-ready email error for book {}", bookId, ex)
        }
    }

    private fun buildBookReadyHtml(name: String?, title: String, previewUrl: String, coverUrl: String? = null): String {
        val hi = name?.trim()?.takeIf { it.isNotBlank() }?.let { "Hi $it, " } ?: ""
        val coverBlock = coverUrl?.let {
            """
                <a href="$previewUrl" style="display:block;margin:0 0 22px;">
                  <img src="$it" alt="$title" width="472" style="display:block;width:100%;max-width:472px;height:auto;border:0;border-radius:12px;" />
                </a>
            """.trimIndent()
        } ?: ""
        return """
        <!doctype html>
        <html>
          <body style="margin:0;padding:0;background:#f3ead8;font-family:Arial,Helvetica,sans-serif;color:#262220;">
            <div style="max-width:520px;margin:0 auto;padding:32px 24px;">
              <img src="https://myatlaso.com/assets/logo-black.png" alt="Atlaso" height="34" style="display:block;height:34px;width:auto;border:0;" />
              <div style="background:#ffffff;border-radius:16px;padding:28px 26px;margin-top:20px;">
                <h1 style="font-size:22px;margin:0 0 8px;color:#262220;">Your photobook is ready 🎉</h1>
                <p style="font-size:15px;line-height:1.6;color:#4a443e;margin:0 0 22px;">
                  ${hi}we've finished designing <strong>$title</strong>. Take a look, and when you're happy with it, order your printed copy.
                </p>
                $coverBlock
                <a href="$previewUrl" style="display:inline-block;background:#c9352c;color:#ffffff;text-decoration:none;font-weight:700;font-size:15px;padding:13px 26px;border-radius:999px;">
                  View your book &rarr;
                </a>
                <p style="font-size:13px;line-height:1.6;color:#8a7f6f;margin:22px 0 0;">
                  Or paste this link into your browser:<br/>
                  <a href="$previewUrl" style="color:#c9352c;word-break:break-all;">$previewUrl</a>
                </p>
              </div>
              <p style="font-size:12px;color:#8a7f6f;text-align:center;margin:22px 0 0;line-height:1.6;">
                Questions? Just reply to this email or write to
                <a href="mailto:support@myatlaso.com" style="color:#c9352c;">support@myatlaso.com</a>.<br/>
                Atlaso · <a href="https://myatlaso.com" style="color:#c9352c;">myatlaso.com</a>
              </p>
            </div>
          </body>
        </html>
        """.trimIndent()
    }

    /** Emails the order confirmation to the customer, attaching the receipt PDF when provided. */
    fun sendOrderConfirmation(order: Order, receiptPdf: ByteArray?) {
        val to = order.customerEmail?.takeIf { it.isNotBlank() }
        if (apiKey.isBlank()) {
            logger.warn("Brevo API key not configured — skipping order confirmation email")
            return
        }
        if (to == null) {
            logger.warn("Order ATL-{} has no customer email — skipping confirmation email", order.number)
            return
        }

        val body = mutableMapOf<String, Any>(
            "sender" to mapOf("name" to senderName, "email" to senderEmail),
            "to" to listOf(mapOf("email" to to, "name" to (order.customerName ?: to))),
            "subject" to "Your Atlaso order is confirmed — ATL-${order.number}",
            "htmlContent" to buildHtml(order),
        )
        if (receiptPdf != null) {
            body["attachment"] = listOf(
                mapOf(
                    "content" to Base64.getEncoder().encodeToString(receiptPdf),
                    "name" to "atlaso-receipt-ATL-R-${order.number}.pdf",
                )
            )
        }

        try {
            val request = Request.Builder()
                .url("https://api.brevo.com/v3/smtp/email")
                .header("api-key", apiKey)
                .header("accept", "application/json")
                .post(objectMapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    logger.info("Sent order confirmation email for ATL-{} to {}", order.number, to)
                } else {
                    logger.error(
                        "Brevo email failed for ATL-{}: HTTP {} {}",
                        order.number, response.code, response.body?.string().orEmpty()
                    )
                }
            }
        } catch (ex: Exception) {
            logger.error("Brevo email error for ATL-{}", order.number, ex)
        }
    }

    /** Formats paise as ₹ with 2 decimals only when the amount isn't whole rupees. */
    private fun money(minor: Long): String {
        val fmt = NumberFormat.getNumberInstance(Locale("en", "IN"))
        if (minor % 100L == 0L) {
            fmt.maximumFractionDigits = 0
        } else {
            fmt.minimumFractionDigits = 2
            fmt.maximumFractionDigits = 2
        }
        return "₹" + fmt.format(BigDecimal(minor).movePointLeft(2))
    }

    private fun buildHtml(order: Order): String {
        val discount = order.discountMinor ?: 0L
        val subtotalMinor = order.amountMinor + discount
        val total = money(order.amountMinor)
        val discountRow = if (discount > 0L) {
            val label = order.couponCode?.let { "Coupon ($it)" } ?: "Discount"
            """<tr><td style="padding:6px 0;color:#1e8a5f;">$label</td><td style="padding:6px 0;text-align:right;color:#1e8a5f;">-${money(discount)}</td></tr>"""
        } else ""
        val title = order.bookTitle?.let { "$it Travel Photobook" } ?: "Travel Photobook"
        val name = order.customerName?.substringBefore(" ") ?: "there"

        val shippingBlock = if (!order.addressLine1.isNullOrBlank()) {
            val lines = listOfNotNull(
                order.customerName,
                order.addressLine1,
                order.addressLine2?.takeIf { it.isNotBlank() },
                listOfNotNull(order.city, order.state, order.pincode).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
                order.shipCountry,
                order.phone,
            ).joinToString("<br/>")
            """
            <div style="margin-top:20px;padding-top:16px;border-top:1px solid #ece5d8;">
              <div style="font-size:11px;text-transform:uppercase;letter-spacing:0.12em;color:#8a7f6f;margin-bottom:6px;">Shipping to</div>
              <div style="font-size:14px;line-height:1.6;color:#262220;">$lines</div>
            </div>
            """.trimIndent()
        } else ""
        return """
        <!doctype html>
        <html>
          <body style="margin:0;padding:0;background:#f3ead8;font-family:Arial,Helvetica,sans-serif;color:#262220;">
            <div style="max-width:520px;margin:0 auto;padding:32px 24px;">
              <img src="https://myatlaso.com/assets/logo-black.png" alt="Atlaso" height="34" style="display:block;height:34px;width:auto;border:0;" />
              <div style="background:#ffffff;border-radius:16px;padding:28px 26px;margin-top:20px;">
                <h1 style="font-size:22px;margin:0 0 8px;color:#262220;">Your order is confirmed 🎉</h1>
                <p style="font-size:15px;line-height:1.6;color:#4a443e;margin:0 0 20px;">
                  Hi $name, thanks for your order! Your photobook is on its way to the press.
                </p>

                <table style="width:100%;border-collapse:collapse;font-size:14px;color:#262220;">
                  <tr><td style="padding:6px 0;color:#8a7f6f;">Order number</td><td style="padding:6px 0;text-align:right;font-weight:700;">ATL-${order.number}</td></tr>
                  <tr><td style="padding:6px 0;color:#8a7f6f;">Book</td><td style="padding:6px 0;text-align:right;">$title</td></tr>
                  <tr><td style="padding:6px 0;color:#8a7f6f;">Quantity</td><td style="padding:6px 0;text-align:right;">${order.quantity}</td></tr>
                  <tr><td style="padding:10px 0 6px;border-top:1px solid #ece5d8;color:#8a7f6f;">Subtotal</td><td style="padding:10px 0 6px;border-top:1px solid #ece5d8;text-align:right;">${money(subtotalMinor)}</td></tr>
                  $discountRow
                  <tr><td style="padding:6px 0 0;font-weight:800;">Total paid</td><td style="padding:6px 0 0;text-align:right;font-weight:800;">$total</td></tr>
                </table>

                $shippingBlock

                <p style="font-size:14px;line-height:1.6;color:#4a443e;margin:22px 0 0;">
                  We'll print and ship your book in about 3 days, and email you the moment it's on its way.
                  Your payment receipt is attached to this email.
                </p>
              </div>

              <p style="font-size:12px;color:#8a7f6f;text-align:center;margin:22px 0 0;line-height:1.6;">
                Questions? Just reply to this email or write to
                <a href="mailto:support@myatlaso.com" style="color:#c9352c;">support@myatlaso.com</a>.<br/>
                Atlaso · <a href="https://myatlaso.com" style="color:#c9352c;">myatlaso.com</a>
              </p>
            </div>
          </body>
        </html>
        """.trimIndent()
    }
}
