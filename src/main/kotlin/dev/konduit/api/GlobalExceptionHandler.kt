package dev.konduit.api

import dev.konduit.api.dto.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

/**
 * Global exception handler producing standard error responses (PRD §5.5).
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(
        ex: IllegalArgumentException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Bad request: {}", ex.message)
        return buildResponse(HttpStatus.BAD_REQUEST, ex.message, request)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableMessage(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Unreadable request body: {}", ex.message)
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid request body", request)
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(
        ex: NoSuchElementException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Not found: {}", ex.message)
        return buildResponse(HttpStatus.NOT_FOUND, ex.message, request)
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(
        ex: IllegalStateException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Conflict: {}", ex.message)
        return buildResponse(HttpStatus.CONFLICT, ex.message, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(
        ex: Exception,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.error("Internal server error", ex)
        return buildResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred",
            request
        )
    }

    private fun buildResponse(
        status: HttpStatus,
        message: String?,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val body = ErrorResponse(
            status = status.value(),
            error = status.reasonPhrase,
            message = message ?: "No details available",
            timestamp = Instant.now(),
            path = request.requestURI
        )
        return ResponseEntity.status(status).body(body)
    }
}

