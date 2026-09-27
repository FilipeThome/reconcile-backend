package br.com.reconcile.shared.security

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TokenColumnCipher {
    private const val PREFIX = "enc:v1:"
    private val random = SecureRandom()

    @Volatile
    var key: SecretKey? = null

    fun install(secret: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        key = SecretKeySpec(digest, "AES")
    }

    fun encrypt(plain: String?): String? {
        if (plain == null) return null
        val secret = key ?: return plain
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = iv + body
        return PREFIX + Base64.getEncoder().encodeToString(packed)
    }

    fun decrypt(stored: String?): String? {
        if (stored == null || !stored.startsWith(PREFIX)) return stored
        val secret = key ?: return stored
        val packed = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        if (packed.size <= 12) return stored
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return cipher.doFinal(packed.copyOfRange(12, packed.size)).toString(Charsets.UTF_8)
    }
}

@Component
class TokenColumnCipherInstaller(
    @Value("\${reconcile.jwt.secret}") secret: String,
) {
    init {
        TokenColumnCipher.install(secret)
    }
}

@Converter
class TokenColumnConverter : AttributeConverter<String?, String?> {
    override fun convertToDatabaseColumn(attribute: String?): String? = TokenColumnCipher.encrypt(attribute)

    override fun convertToEntityAttribute(dbData: String?): String? = TokenColumnCipher.decrypt(dbData)
}
