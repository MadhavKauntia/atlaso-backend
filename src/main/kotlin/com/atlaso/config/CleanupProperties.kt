package com.atlaso.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for the scheduled orphaned-data cleanup. All overridable via env
 * (see application.yml `atlaso.cleanup.*`).
 */
@ConfigurationProperties(prefix = "atlaso.cleanup")
data class CleanupProperties(
    /** Master switch. When false, the job logs what it *would* delete but changes nothing. */
    val enabled: Boolean = true,
    /** Unclaimed guest trips that HAVE photos are deleted once older than this many hours. */
    val photoTripCutoffHours: Long = 24 * 7,
    /** Unclaimed guest trips with NO photos are deleted once older than this (shorter) age. */
    val emptyTripCutoffHours: Long = 24 * 3,
    /** Max trips processed per scheduled run (bounds work + object deletes). */
    val batchSize: Int = 200,
)
