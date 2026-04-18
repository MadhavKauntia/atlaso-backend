package com.atlaso.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "atlaso.storage.s3")
data class S3StorageConfig(
    val bucketName: String = "",
    val region: String = "us-east-1"
)
