package com.atlaso.controller

import com.atlaso.controller.dto.AdminOrderDto
import com.atlaso.controller.dto.ShipRequest
import com.atlaso.service.AdminService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Admin dashboard API. Guarded by [com.atlaso.config.AdminKeyInterceptor]
 * (X-Admin-Key header), so no per-user JWT is required.
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminService: AdminService,
) {
    /** Lightweight endpoint the dashboard uses to validate the admin key on login. */
    @GetMapping("/ping")
    fun ping(): ResponseEntity<Map<String, Boolean>> = ResponseEntity.ok(mapOf("ok" to true))

    @GetMapping("/orders")
    fun orders(): ResponseEntity<List<AdminOrderDto>> = ResponseEntity.ok(adminService.listOrders())

    @PostMapping("/orders/{id}/ship")
    fun ship(@PathVariable id: UUID, @RequestBody(required = false) body: ShipRequest?): ResponseEntity<AdminOrderDto> =
        ResponseEntity.ok(adminService.markShipped(id, body?.trackingNumber))

    @GetMapping("/orders/{id}/receipt")
    fun receipt(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (bytes, filename) = adminService.receiptPdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(bytes)
    }

    @GetMapping("/orders/{id}/book-pdf")
    fun bookPdf(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val (bytes, filename) = adminService.bookPdf(id)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(bytes)
    }
}
