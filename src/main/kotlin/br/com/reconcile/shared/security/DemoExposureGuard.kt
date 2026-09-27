package br.com.reconcile.shared.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.net.URI

@Component
class DemoExposureGuard(
    @Value("\${SEED_DEMO:true}") private val seedDemo: Boolean,
    @Value("\${reconcile.cors.origins}") private val origins: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!seedDemo) return
        val publicOrigin = origins.split(",")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !isLocalOrigin(it) }
        check(publicOrigin == null) {
            "SEED_DEMO=true exposes the known pilot password. Set SEED_DEMO=false before allowing CORS origin $publicOrigin."
        }
    }

    companion object {
        fun isLocalOrigin(origin: String): Boolean {
            val host = runCatching { URI(origin).host }.getOrNull()?.trim()?.lowercase() ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        }
    }
}
