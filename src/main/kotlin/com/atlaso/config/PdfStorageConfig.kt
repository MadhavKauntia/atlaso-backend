package com.atlaso.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "atlaso.storage.pdf")
data class PdfStorageConfig(
    val basePath: String = "./uploads/pdfs"
)
