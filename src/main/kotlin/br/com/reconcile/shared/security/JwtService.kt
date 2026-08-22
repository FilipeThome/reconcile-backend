package br.com.reconcile.shared.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${reconcile.jwt.secret}") secret: String,
    @Value("\${reconcile.jwt.expiration-hours}") private val expirationHours: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(validateSecret(secret))

    fun issue(user: AuthUser): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(user.email)
            .claim("uid", user.userId.toString())
            .claim("tid", user.tenantId.toString())
            .claim("tex", user.tenantExternalId)
            .claim("name", user.displayName)
            .claim("role", user.role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(expirationHours, ChronoUnit.HOURS)))
            .signWith(key)
            .compact()
    }

    fun parse(token: String): AuthUser {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        return AuthUser(
            userId = UUID.fromString(claims["uid"].toString()),
            tenantId = UUID.fromString(claims["tid"].toString()),
            tenantExternalId = claims["tex"].toString(),
            displayName = claims["name"].toString(),
            email = claims.subject,
            role = claims["role"].toString(),
        )
    }

    private fun validateSecret(secret: String): ByteArray {
        val bytes = secret.toByteArray(StandardCharsets.UTF_8)
        check(bytes.size >= 32) {
            "JWT_SECRET deve ter pelo menos 32 caracteres. Copie backend/.env.example para .env e gere um valor aleatório."
        }
        check(secret != "change-me-in-production-use-256-bit-secret-key!!") {
            "JWT_SECRET padrão inseguro. Defina um segredo próprio no .env."
        }
        return bytes
    }
}
