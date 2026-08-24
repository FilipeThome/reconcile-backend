package br.com.reconcile.shared.security

import br.com.reconcile.shared.api.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class LoginRateLimiter {
    private data class Bucket(var count: Int, val windowStart: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun assertAllowed(clientKey: String) {
        val now = System.currentTimeMillis()
        val bucket = buckets[clientKey] ?: return
        if (now - bucket.windowStart >= WINDOW_MS) {
            buckets.remove(clientKey, bucket)
            return
        }
        if (bucket.count >= MAX_ATTEMPTS) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT", "Muitas tentativas. Aguarde um minuto.")
        }
    }

    fun recordFailure(clientKey: String) {
        val now = System.currentTimeMillis()
        buckets.compute(clientKey) { _, existing ->
            if (existing == null || now - existing.windowStart >= WINDOW_MS) Bucket(1, now)
            else existing.apply { count += 1 }
        }
    }

    companion object {
        private const val WINDOW_MS = 60_000L
        private const val MAX_ATTEMPTS = 5
    }
}
