package dev.konduit.api.dto

import java.time.Instant

/**
 * Standard error response format returned by [GlobalExceptionHandler] for all API errors.
 */
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
    val path: String
)

