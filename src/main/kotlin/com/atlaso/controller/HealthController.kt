package com.atlaso.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public liveness endpoint for Railway's HTTP healthcheck and external uptime monitors.
 * Deliberately does no DB/downstream calls — a dependency blip shouldn't make a healthy
 * process look dead and get restarted. Permitted in SecurityConfig; excluded from the
 * access log to avoid flooding Axiom with monitor pings.
 */
@RestController
class HealthController {

    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> = ResponseEntity.ok(mapOf("status" to "UP"))
}
