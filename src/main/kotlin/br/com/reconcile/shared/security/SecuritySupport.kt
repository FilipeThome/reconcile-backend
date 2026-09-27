package br.com.reconcile.shared.security

import br.com.reconcile.shared.api.apiMessage
import org.springframework.security.core.context.SecurityContextHolder

fun currentUser(): AuthUser =
    SecurityContextHolder.getContext().authentication?.principal as? AuthUser
        ?: throw IllegalStateException(apiMessage("auth.missingUser"))
