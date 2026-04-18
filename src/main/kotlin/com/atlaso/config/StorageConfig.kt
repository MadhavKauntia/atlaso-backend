package com.atlaso.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "atlaso.storage.local")
data class StorageConfig(
    val basePath: String = "./uploads/photos"
)
