package br.com.reconcile.shared.security

import org.springframework.security.core.context.SecurityContextHolder

fun currentUser(): AuthUser =
    SecurityContextHolder.getContext().authentication?.principal as? AuthUser
        ?: throw IllegalStateException("Usuário autenticado ausente")
