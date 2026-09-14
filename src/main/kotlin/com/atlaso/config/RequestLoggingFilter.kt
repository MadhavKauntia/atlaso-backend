package com.atlaso.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * One access-log line per HTTP request: method, path, status, and duration. The app runs
 * the OpenTelemetry agent with auto-instrumentation disabled (logs-only), so without this
 * there would be no request-level record in Axiom — no way to see traffic, status codes,
 * latency, or correlate a failure to a call. Registered outermost so it also captures
 * requests rejected by the security chain (401/403) with the final status.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger("http.access")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val start = System.currentTimeMillis()
        try {
            chain.doFilter(request, response)
        } finally {
            val ms = System.currentTimeMillis() - start
            val query = request.queryString?.let { "?$it" } ?: ""
            log.info("{} {}{} -> {} ({} ms)", request.method, request.requestURI, query, response.status, ms)
        }
    }

    // Skip CORS preflight noise.
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method.equals("OPTIONS", ignoreCase = true)
}
