package com.atlaso.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.security.MessageDigest

/**
 * Guards the admin API (paths under /api/admin) with a shared secret sent in the
 * `X-Admin-Key` header, compared constant-time against the ADMIN_API_KEY env var.
 * Preflight (OPTIONS) requests pass through for CORS.
 */
@Component
class AdminKeyInterceptor(
    @Value("\${admin.api-key}") private val adminKey: String,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return true

        val provided = request.getHeader("X-Admin-Key")
        val ok = adminKey.isNotBlank() && provided != null &&
            MessageDigest.isEqual(adminKey.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))

        if (!ok) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Unauthorized"}""")
            return false
        }
        return true
    }
}
