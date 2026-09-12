package com.atlaso.controller

import com.atlaso.service.OrderService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class ReceiptController(
    private val orderService: OrderService,
) {
    @GetMapping("/trips/{tripId}/receipt")
    fun downloadReceipt(
        @PathVariable tripId: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ByteArray> {
        val userId = UUID.fromString(jwt.subject)
        val (pdfBytes, filename) = orderService.generateReceipt(tripId, userId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(pdfBytes)
    }
}
