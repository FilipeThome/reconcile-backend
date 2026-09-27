package br.com.reconcile.shared.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityHardeningTest {
    @Test
    fun `token column cipher round trips and leaves legacy plaintext readable`() {
        val previous = TokenColumnCipher.key
        try {
            TokenColumnCipher.install("unit-test-secret-key-32-bytes-min!!unit-test-secret-key-32-bytes")
            val stored = TokenColumnCipher.encrypt("seller-token")
            assertTrue(stored!!.startsWith("enc:v1:"))
            assertEquals("seller-token", TokenColumnCipher.decrypt(stored))
            assertEquals("legacy", TokenColumnCipher.decrypt("legacy"))
            assertEquals(null, TokenColumnCipher.encrypt(null))
            val converter = TokenColumnConverter()
            assertEquals("seller-token", converter.convertToEntityAttribute(converter.convertToDatabaseColumn("seller-token")))
        } finally {
            TokenColumnCipher.key = previous
        }
    }

    @Test
    fun `demo seed refuses a public cors origin`() {
        assertTrue(DemoExposureGuard.isLocalOrigin("http://localhost:5173"))
        assertTrue(DemoExposureGuard.isLocalOrigin("http://127.0.0.1:8081"))
        assertFalse(DemoExposureGuard.isLocalOrigin("https://app.example.com"))
        assertFalse(DemoExposureGuard.isLocalOrigin("not a url"))

        DemoExposureGuard(seedDemo = false, origins = "https://app.example.com").run(DefaultApplicationArguments())
        DemoExposureGuard(seedDemo = true, origins = "http://localhost:5173,http://127.0.0.1:8081").run(DefaultApplicationArguments())
        assertThrows<IllegalStateException> {
            DemoExposureGuard(seedDemo = true, origins = "http://localhost:5173,https://app.example.com").run(DefaultApplicationArguments())
        }
    }

    @Test
    fun `forwarded chain wins over a client supplied real ip`() {
        val trusted = ClientIpResolver("10.0.0.5")
        val request = MockHttpServletRequest()
        request.remoteAddr = "10.0.0.5"
        request.addHeader("X-Real-IP", "198.51.100.4")
        request.addHeader("X-Forwarded-For", "198.51.100.9, 203.0.113.10")
        assertEquals("203.0.113.10", trusted.clientKey(request))
    }
}
