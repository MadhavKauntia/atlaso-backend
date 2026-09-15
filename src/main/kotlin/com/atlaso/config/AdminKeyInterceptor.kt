package com.atlaso.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Guards the admin API (paths under /api/admin) with a shared secret sent in the
 * `X-Admin-Key` header, compared constant-time against the ADMIN_API_KEY env var.
 * Preflight (OPTIONS) requests pass through for CORS.
 *
 * Since the secret is static and unauthenticated, failed attempts are rate-limited
 * per client IP to blunt brute force: after [MAX_FAILURES] bad attempts within
 * [WINDOW_MS], that IP is locked out (429) for the remainder of the window. A
 * successful auth clears the IP's failure record.
 */
@Component
class AdminKeyInterceptor(
    @Value("\${admin.api-key}") private val adminKey: String,
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(AdminKeyInterceptor::class.java)

    private class Attempt(@Volatile var count: Int, @Volatile var windowStart: Long)

    private val failures = ConcurrentHashMap<String, Attempt>()

    private companion object {
        const val MAX_FAILURES = 10
        const val WINDOW_MS = 15 * 60 * 1000L // 15 minutes
        const val MAX_TRACKED_IPS = 10_000
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return true

        val ip = clientIp(request)
        val now = System.currentTimeMillis()

        val existing = failures[ip]
        if (existing != null && now - existing.windowStart < WINDOW_MS && existing.count >= MAX_FAILURES) {
            val retryAfter = ((WINDOW_MS - (now - existing.windowStart)) / 1000).coerceAtLeast(1)
            logger.warn("Admin auth rate-limited for {} ({} failures)", ip, existing.count)
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", retryAfter.toString())
            response.contentType = "application/json"
            response.writer.write("""{"error":"Too many attempts. Try again later."}""")
            return false
        }

        val provided = request.getHeader("X-Admin-Key")
        val ok = adminKey.isNotBlank() && provided != null &&
            MessageDigest.isEqual(adminKey.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))

        if (!ok) {
            recordFailure(ip, now)
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Unauthorized"}""")
            return false
        }

        failures.remove(ip) // a valid key clears any prior failures for this IP
        return true
    }

    private fun recordFailure(ip: String, now: Long) {
        if (failures.size > MAX_TRACKED_IPS) {
            failures.entries.removeIf { now - it.value.windowStart >= WINDOW_MS }
        }
        failures.compute(ip) { _, cur ->
            if (cur == null || now - cur.windowStart >= WINDOW_MS) Attempt(1, now)
            else { cur.count++; cur }
        }
    }

    /** First hop in X-Forwarded-For (Railway edge), falling back to the socket address. */
    private fun clientIp(request: HttpServletRequest): String {
        val xff = request.getHeader("X-Forwarded-For")
        return if (!xff.isNullOrBlank()) xff.substringBefore(",").trim() else request.remoteAddr ?: "unknown"
    }
}
