package com.atlaso.controller.dto

import java.time.Instant

data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String?,
    val timestamp: Instant = Instant.now(),
    val path: String? = null
)
