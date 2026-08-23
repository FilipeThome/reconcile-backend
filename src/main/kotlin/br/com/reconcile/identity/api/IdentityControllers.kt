package br.com.reconcile.identity.api

import br.com.reconcile.identity.application.AuthService
import br.com.reconcile.identity.application.LoginResponse
import br.com.reconcile.identity.infra.SellerAccountRepository
import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.identity.infra.UserRepository
import br.com.reconcile.shared.security.LoginRateLimiter
import br.com.reconcile.shared.security.currentUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(
    @field:NotBlank @field:Email @field:Size(max = 255) val email: String,
    @field:NotBlank @field:Size(min = 1, max = 128) val password: String,
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val loginRateLimiter: LoginRateLimiter,
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, http: HttpServletRequest): LoginResponse {
        loginRateLimiter.check(clientKey(http))
        return authService.login(request.email, request.password)
    }

    private fun clientKey(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Real-IP")?.trim().orEmpty()
        val remote = request.remoteAddr.orEmpty()
        return if (forwarded.isNotEmpty() && isTrustedProxy(remote)) forwarded else remote.ifBlank { "unknown" }
    }

    private fun isTrustedProxy(ip: String) =
        ip == "127.0.0.1" || ip == "https://example.net/id/garnet" || ip.startsWith("10.") || ip.startsWith("172.") || ip.startsWith("192.168.")
}

@RestController
@RequestMapping("/api/v1/settings")
class SettingsController(
    private val tenants: TenantRepository,
    private val users: UserRepository,
    private val sellers: SellerAccountRepository,
) {
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun settings(): Map<String, Any> {
        val auth = currentUser()
        val tenant = tenants.findById(auth.tenantId).orElseThrow()
        return mapOf(
            "tenant" to mapOf(
                "id" to tenant.externalId,
                "name" to tenant.name,
                "sellerId" to (sellers.findByTenantId(tenant.id).firstOrNull()?.sellerExternalId ?: ""),
            ),
            "currentUser" to mapOf(
                "name" to auth.displayName,
                "email" to auth.email,
                "role" to auth.role,
            ),
            "users" to users.findByTenantId(tenant.id).map { mapOf("name" to it.name, "email" to it.email, "role" to it.role) },
            "tolerances" to mapOf(
                "cents" to tenant.amountTolerance,
                "dateDays" to tenant.dateToleranceDays,
                "settlementDeadlineDays" to tenant.settlementDeadlineDays,
                "rawEventRetentionDays" to tenant.rawEventRetentionDays,
            ),
        )
    }
}
