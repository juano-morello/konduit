package dev.konduit.api

import dev.konduit.api.dto.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

/**
 * Global exception handler producing standard error responses (PRD §5.5).
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationErrors(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.joinToString("; ") { error ->
            "${error.field}: ${error.defaultMessage}"
        }
        val message = fieldErrors.ifBlank { "Validation failed" }
        log.warn("Validation failed: {}", message)
        return buildResponse(HttpStatus.BAD_REQUEST, message, request)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val violations = ex.constraintViolations.joinToString("; ") { violation ->
            "${violation.propertyPath}: ${violation.message}"
        }
        val message = violations.ifBlank { "Constraint violation" }
        log.warn("Constraint violation: {}", message)
        return buildResponse(HttpStatus.BAD_REQUEST, message, request)
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(
        ex: MissingServletRequestParameterException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val message = "Required parameter '${ex.parameterName}' of type ${ex.parameterType} is missing"
        log.warn("Missing parameter: {}", message)
        return buildResponse(HttpStatus.BAD_REQUEST, message, request)
    }

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

