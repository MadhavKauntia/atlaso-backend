package com.atlaso.controller

import com.atlaso.service.StorageService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/internal")
@ConditionalOnProperty(name = ["atlaso.storage.type"], havingValue = "local", matchIfMissing = true)
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
}
