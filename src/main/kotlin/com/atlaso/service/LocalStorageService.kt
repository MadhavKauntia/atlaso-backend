package com.atlaso.service

import com.atlaso.config.StorageConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.net.URLEncoder
import java.util.Base64

@Service
@ConditionalOnProperty(name = ["atlaso.storage.type"], havingValue = "local", matchIfMissing = true)
class LocalStorageService(
    private val storageConfig: StorageConfig
) : StorageService {

    private val logger = LoggerFactory.getLogger(LocalStorageService::class.java)

    override fun store(key: String, inputStream: InputStream, contentType: String): String {
        val filePath = resolveFilePath(key)
        Files.createDirectories(filePath.parent)
        Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)
        logger.info("Stored file at: {}", filePath)
        return key
    }

    override fun load(key: String): ByteArray {
        val filePath = resolveFilePath(key)
        return Files.readAllBytes(filePath)
    }

    override fun getAccessUrl(key: String, contentType: String): String {
        val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
        return "http://localhost:8080/api/internal/file?key=$encodedKey"
    }

    override fun getUploadUrl(key: String, contentType: String, contentLength: Long): String {
        val encodedKey = URLEncoder.encode(key, "UTF-8")
        return "http://localhost:8080/api/internal/upload?key=$encodedKey"
    }

    override fun delete(key: String) {
        val filePath = resolveFilePath(key)
        Files.deleteIfExists(filePath)
        logger.info("Deleted file at: {}", filePath)
    }

    override fun exists(key: String): Boolean = Files.exists(resolveFilePath(key))

    /**
     * Resolves [key] under the storage base directory, guarding against path traversal:
     * the normalized absolute path must stay inside the base. A key like `../../etc/passwd`
     * is rejected instead of escaping the storage root.
     */
    private fun resolveFilePath(key: String): Path {
        val base = Paths.get(storageConfig.basePath).toAbsolutePath().normalize()
        val resolved = base.resolve(key).normalize()
        require(resolved.startsWith(base)) { "Illegal storage key (path traversal): $key" }
        return resolved
    }
}
