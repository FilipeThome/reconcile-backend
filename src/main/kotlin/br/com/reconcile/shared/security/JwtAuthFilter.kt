package br.com.reconcile.shared.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import br.com.reconcile.shared.api.apiErrorBody
import br.com.reconcile.shared.api.apiMessage
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val sessionUsers: SessionUserLoader,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.servletPath.ifBlank { request.requestURI.substringBefore('?') }
        return path == "/api/v1/auth/login" || path == "/api/v1/health" || path.startsWith("/api/v1/integrations/oauth/") ||
            path.startsWith("/api/v1/integrations/webhooks/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.removePrefix("Bearer ").trim()
            if (token.isEmpty()) {
                unauthorized(response, "auth.tokenMissing")
                return
            }
            val parsed = try {
                jwtService.parse(token)
            } catch (_: Exception) {
                unauthorized(response, "auth.tokenInvalid")
                return
            }
            val user = sessionUsers.hydrate(parsed)
            if (user == null) {
                unauthorized(response, "auth.sessionInvalid")
                return
            }
            val tenantHeader = request.getHeader("X-Tenant-Id")
            if (!tenantHeader.isNullOrBlank() && tenantHeader != user.tenantExternalId && tenantHeader != user.tenantId.toString()) {
                response.status = 403
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.writer.write(apiErrorBody("FORBIDDEN", apiMessage("auth.tenantMismatch")))
                return
            }
            MDC.put("tenantId", user.tenantExternalId)
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(user, null, user.authorities)
        }
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("tenantId")
        }
    }

    private fun unauthorized(response: HttpServletResponse, key: String) {
        response.status = 401
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(apiErrorBody("UNAUTHORIZED", apiMessage(key)))
    }
}
