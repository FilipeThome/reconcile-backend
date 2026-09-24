package br.com.reconcile.integration.ingestion.amazon

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AmazonRequestSigner {
    private val amzDateFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    private val emptyPayloadHash = sha256Hex("")

    fun headers(
        method: String,
        uri: URI,
        lwaAccessToken: String,
        awsAccessKey: String,
        awsSecretKey: String,
        region: String,
        sessionToken: String?,
        now: Instant,
    ): Map<String, String> {
        val amzDate = amzDateFormat.format(now)
        val date = dateFormat.format(now)
        val host = uri.host.lowercase()
        val canonicalHeaders = buildString {
            append("host:").append(host).append('\n')
            append("x-amz-access-token:").append(lwaAccessToken).append('\n')
            append("x-amz-date:").append(amzDate).append('\n')
            if (!sessionToken.isNullOrBlank()) append("x-amz-security-token:").append(sessionToken).append('\n')
        }
        val signedHeaders = if (sessionToken.isNullOrBlank()) {
            "host;x-amz-access-token;x-amz-date"
        } else {
            "host;x-amz-access-token;x-amz-date;x-amz-security-token"
        }
        val canonical = listOf(
            method.uppercase(),
            canonicalPath(uri),
            canonicalQuery(uri),
            canonicalHeaders,
            signedHeaders,
            emptyPayloadHash,
        ).joinToString("\n")
        val scope = "$date/$region/execute-api/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonical)).joinToString("\n")
        val signature = hmacHex(signingKey(awsSecretKey, date, region), stringToSign)
        val authorization = "AWS4-HMAC-SHA256 Credential=$awsAccessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return buildMap {
            put("host", host)
            put("x-amz-access-token", lwaAccessToken)
            put("x-amz-date", amzDate)
            if (!sessionToken.isNullOrBlank()) put("x-amz-security-token", sessionToken)
            put("Authorization", authorization)
        }
    }

    private fun canonicalPath(uri: URI): String {
        val raw = uri.rawPath.ifBlank { "/" }
        return raw.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) "" else encode(decode(segment))
        }.ifBlank { "/" }
    }

    private fun canonicalQuery(uri: URI): String {
        val raw = uri.rawQuery ?: return ""
        return raw.split('&')
            .filter { it.isNotEmpty() }
            .map { part ->
                val eq = part.indexOf('=')
                val name = if (eq < 0) part else part.substring(0, eq)
                val value = if (eq < 0) "" else part.substring(eq + 1)
                encode(decode(name)) to encode(decode(value))
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun signingKey(secret: String, date: String, region: String): ByteArray {
        val dateKey = hmac("AWS4$secret".toByteArray(StandardCharsets.UTF_8), date)
        val regionKey = hmac(dateKey, region)
        val serviceKey = hmac(regionKey, "execute-api")
        return hmac(serviceKey, "aws4_request")
    }

    private fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, value: String) = hex(hmac(key, value))

    private fun sha256Hex(value: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun encode(value: String): String =
        buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val b = byte.toInt() and 0xff
                if (b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code ||
                    b == '-'.code || b == '_'.code || b == '.'.code || b == '~'.code
                ) {
                    append(b.toChar())
                } else {
                    append('%')
                    append("%02X".format(b))
                }
            }
        }

    private fun decode(value: String): String = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
}
