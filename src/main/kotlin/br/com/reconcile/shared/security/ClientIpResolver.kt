package br.com.reconcile.shared.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ClientIpResolver(
    @Value("\${reconcile.auth.trusted-proxies:}") raw: String,
) {
    private val trusted = raw.split(",")
        .map { normalize(it) }
        .filter { it.isNotEmpty() }
        .toSet()

    fun clientKey(request: HttpServletRequest): String {
        val remote = normalize(request.remoteAddr)
        if (remote.isEmpty()) return "unknown"
        if (!trusted.contains(remote)) return remote
        val forwarded = forwardedClient(request)
        return forwarded.ifBlank { remote }
    }

    private fun forwardedClient(request: HttpServletRequest): String {
        val realIp = normalize(request.getHeader("X-Real-IP"))
        if (realIp.isNotEmpty()) return realIp
        val forwarded = request.getHeader("X-Forwarded-For").orEmpty()
            .split(",")
            .map { normalize(it) }
            .lastOrNull { it.isNotEmpty() }
            .orEmpty()
        return forwarded
    }

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        var ip = value.trim().removePrefix("[").removeSuffix("]")
        if (ip.startsWith("::ffff:")) ip = ip.removePrefix("::ffff:")
        return if (ip.matches(IPV4) || ip.matches(IPV6)) ip else ""
    }

    companion object {
        private val IPV4 = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
        private val IPV6 = Regex("""^[0-9a-fA-F:]+$""")
    }
}
