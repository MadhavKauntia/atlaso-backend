package com.atlaso.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Per-user rate limiting on the cost-sensitive write endpoints. Keyed by JWT subject (these
 * endpoints require auth), falling back to remote address. Returns 429 when exceeded. Limits
 * are generous — meant to stop abuse/runaway OpenAI spend, not normal iteration — and tunable
 * via env (atlaso.ratelimit.*).
 */
@Component
class RateLimitInterceptor(
    private val limiter: RateLimiter,
    @Value("\${atlaso.ratelimit.generate-per-hour:40}") private val generatePerHour: Int,
    @Value("\${atlaso.ratelimit.upload-per-hour:60}") private val uploadPerHour: Int,
    @Value("\${atlaso.ratelimit.order-per-hour:30}") private val orderPerHour: Int,
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(RateLimitInterceptor::class.java)
    private val matcher = AntPathMatcher()

    private data class Rule(val method: String, val pattern: String, val limit: Int)

    private val windowSeconds = 3600L
    private val rules by lazy {
        listOf(
            Rule("POST", "/api/trips/*/book/generate", generatePerHour),
            Rule("POST", "/api/books/*/regenerate", generatePerHour),
            Rule("POST", "/api/trips/*/photos/bulk", uploadPerHour),
            Rule("POST", "/api/payments/create-order", orderPerHour),
        )
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val rule = rules.firstOrNull {
            it.method.equals(request.method, ignoreCase = true) && matcher.match(it.pattern, request.requestURI)
        } ?: return true

        val principal = principalKey(request)
        if (limiter.tryAcquire("$principal|${rule.pattern}", rule.limit, windowSeconds)) return true

        logger.warn("Rate limit exceeded: {} {} by {} (limit {}/h)", request.method, request.requestURI, principal, rule.limit)
        response.status = 429
        response.contentType = "application/json"
        response.writer.write("""{"error":"Rate limit exceeded. Please slow down and try again shortly."}""")
        return false
    }

    private fun principalKey(request: HttpServletRequest): String {
        val sub = (SecurityContextHolder.getContext().authentication?.principal as? Jwt)?.subject
        return sub ?: (request.remoteAddr ?: "anon")
    }
}
