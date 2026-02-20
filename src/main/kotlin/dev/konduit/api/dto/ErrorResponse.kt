package dev.konduit.api.dto

import java.time.Instant

/**
 * Standard error response format (PRD §5.5).
 */
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val path: String
)

