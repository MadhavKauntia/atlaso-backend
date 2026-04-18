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
        val bytes = load(key)
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return "data:$contentType;base64,$base64"
    }

    override fun delete(key: String) {
        val filePath = resolveFilePath(key)
        Files.deleteIfExists(filePath)
        logger.info("Deleted file at: {}", filePath)
    }

    private fun resolveFilePath(key: String): Path {
        return Paths.get(storageConfig.basePath).resolve(key)
    }
}
