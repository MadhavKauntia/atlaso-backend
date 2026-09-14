package com.atlaso.service

import com.atlaso.config.StorageConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path

class LocalStorageServiceTest {

    @Test
    fun `rejects path traversal keys`(@TempDir base: Path) {
        val svc = LocalStorageService(StorageConfig(basePath = base.toString()))
        val bytes = ByteArrayInputStream("x".toByteArray())

        // Reads, writes and deletes with an escaping key must be refused.
        assertThrows(IllegalArgumentException::class.java) { svc.load("../../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { svc.store("../escape.txt", bytes, "text/plain") }
        assertThrows(IllegalArgumentException::class.java) { svc.delete("../../secret") }
    }

    @Test
    fun `allows a normal key inside the base dir`(@TempDir base: Path) {
        val svc = LocalStorageService(StorageConfig(basePath = base.toString()))
        svc.store("trip/photo.jpg", ByteArrayInputStream("hello".toByteArray()), "image/jpeg")
        assertEquals("hello", String(svc.load("trip/photo.jpg")))
    }
}
