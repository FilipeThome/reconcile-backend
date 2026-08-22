package br.com.reconcile.shared.api

import org.springframework.http.HttpStatus

open class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)

class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, "CONFLICT", message)

class UnprocessableException(message: String) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message)

class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message)
