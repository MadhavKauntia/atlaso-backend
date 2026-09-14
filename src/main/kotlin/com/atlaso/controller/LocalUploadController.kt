package com.atlaso.controller

import com.atlaso.service.StorageService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/internal")
// Dev-only. Must be explicitly enabled (STORAGE_TYPE=local); NOT enabled when the setting is
// missing, so a misconfigured production never exposes these public read/write endpoints.
@ConditionalOnProperty(name = ["atlaso.storage.type"], havingValue = "local", matchIfMissing = false)
class LocalUploadController(
    private val storageService: StorageService
) {
    @PutMapping("/upload")
    fun handleUpload(
        @RequestParam key: String,
        request: HttpServletRequest
    ): ResponseEntity<Void> {
        val contentType = request.contentType ?: "application/octet-stream"
        storageService.store(key, request.inputStream, contentType)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/file")
    fun serveFile(@RequestParam key: String): ResponseEntity<ByteArray> {
        val bytes = storageService.load(key)
        val contentType = when {
            key.endsWith(".jpg") || key.endsWith(".jpeg") -> "image/jpeg"
            key.endsWith(".png") -> "image/png"
            key.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(bytes)
    }
}
