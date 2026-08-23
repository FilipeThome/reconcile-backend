package br.com.reconcile.shared.api

import br.com.reconcile.closing.domain.IllegalClosingTransitionException
import br.com.reconcile.reconciliation.domain.IllegalCaseTransitionException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
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
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse("CONFLICT", ex.message ?: "invalid_transition"))

    @ExceptionHandler(BadCredentialsException::class)
    fun handleAuth() = ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("UNAUTHORIZED", "Credenciais inválidas"))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleDenied() = ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse("FORBIDDEN", "Acesso negado"))

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        ConstraintViolationException::class,
        DateTimeParseException::class,
        IllegalArgumentException::class,
    )
    fun handleValidation() = ResponseEntity.badRequest().body(ErrorResponse("VALIDATION", "Requisição inválida"))

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException) =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse("UNAUTHORIZED", ex.message ?: "Usuário autenticado ausente"))

    @ExceptionHandler(Exception::class)
    fun handleGeneric() = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse("ERROR", "Falha interna"))
}
