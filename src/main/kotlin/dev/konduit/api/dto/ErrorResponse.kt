package dev.konduit.api.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Standard error response format returned by [GlobalExceptionHandler] for all API errors.
 */
@Schema(description = "Standard error response")
data class ErrorResponse(
    @Schema(description = "HTTP status code", example = "400")
    val status: Int,
    @Schema(description = "HTTP error reason phrase", example = "Bad Request")
    val error: String,
    @Schema(description = "Detailed error message")
    val message: String,
    @Schema(description = "Timestamp when the error occurred")
    val timestamp: Instant = Instant.now(),
    @Schema(description = "Request path that caused the error")
    val path: String
)

