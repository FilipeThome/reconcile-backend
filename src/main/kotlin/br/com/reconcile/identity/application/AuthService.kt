package br.com.reconcile.identity.application

import br.com.reconcile.identity.infra.UserRepository
import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.shared.security.AuthUser
import br.com.reconcile.shared.security.JwtService
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val users: UserRepository,
    private val tenants: TenantRepository,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
) {
    fun login(email: String, password: String): LoginResponse {
        val user = users.findByEmailIgnoreCase(email.trim()) ?: throw BadCredentialsException("invalid")
        if (!encoder.matches(password, user.passwordHash)) throw BadCredentialsException("invalid")
        val tenant = tenants.findById(user.tenantId).orElseThrow { BadCredentialsException("invalid") }
        val principal = AuthUser(user.id, user.tenantId, tenant.externalId, user.name, user.email, user.role)
        return LoginResponse(jwt.issue(principal), principal.displayName, principal.role, tenant.externalId)
    }
}

data class LoginResponse(val token: String, val name: String, val role: String, val tenantId: String)
