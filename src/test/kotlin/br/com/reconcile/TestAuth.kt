package br.com.reconcile

import br.com.reconcile.shared.security.AuthUser
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

object TestAuth {
    const val JWT_SECRET = "1234567890123456789012345678901234567890123456789012345678901234"
    val tenantId: UUID = UUID.fromString("8f2c1a00-0000-4000-8000-000000000001")
    val userId: UUID = UUID.fromString("8f2c1a00-0000-4000-8000-000000000002")

    val user = AuthUser(
        userId = userId,
        tenantId = tenantId,
        tenantExternalId = "ten_8f2c1a",
        displayName = "Ana Dantas",
        email = "ana.dantas@lojaexemplo.com.br",
        role = "admin",
    )

    fun login(role: String = "admin") {
        val principal = user.copy(role = role)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    fun logout() {
        SecurityContextHolder.clearContext()
    }
}
