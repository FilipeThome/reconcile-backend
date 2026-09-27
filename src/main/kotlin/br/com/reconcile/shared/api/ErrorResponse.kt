package br.com.reconcile.shared.api

import br.com.reconcile.closing.domain.IllegalClosingTransitionException
import br.com.reconcile.reconciliation.domain.IllegalCaseTransitionException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.format.DateTimeParseException

data class ErrorResponse(val code: String, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException) = ResponseEntity.status(ex.status).body(ErrorResponse(ex.code, ex.message ?: ex.code))

    @ExceptionHandler(IllegalCaseTransitionException::class, IllegalClosingTransitionException::class)
    fun handleDomain(ex: RuntimeException) =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("CONFLICT", ex.message ?: apiMessage("transition.invalid")))

    @ExceptionHandler(BadCredentialsException::class)
    fun handleAuth() = ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("UNAUTHORIZED", apiMessage("auth.badCredentials")))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleDenied() = ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse("FORBIDDEN", apiMessage("auth.denied")))

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        ConstraintViolationException::class,
        DateTimeParseException::class,
        IllegalArgumentException::class,
    )
    fun handleValidation() = ResponseEntity.badRequest().body(ErrorResponse("VALIDATION", apiMessage("auth.invalidRequest")))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethod() =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse("METHOD_NOT_ALLOWED", apiMessage("auth.method")))

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException) =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("UNAUTHORIZED", ex.message ?: apiMessage("auth.missingUser")))

    @ExceptionHandler(Exception::class)
    fun handleGeneric() = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("ERROR", apiMessage("auth.internal")))
}
