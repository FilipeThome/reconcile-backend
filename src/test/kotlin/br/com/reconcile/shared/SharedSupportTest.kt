package br.com.reconcile.shared

import br.com.reconcile.TestAuth
import br.com.reconcile.closing.domain.IllegalClosingTransitionException
import br.com.reconcile.fiscal.infra.StubFiscalAdapter
import br.com.reconcile.reconciliation.domain.IllegalCaseTransitionException
import br.com.reconcile.reconciliation.domain.MatchingEngine
import br.com.reconcile.reconciliation.domain.MatchingLayer
import br.com.reconcile.shared.api.ApiException
import br.com.reconcile.shared.api.ConflictException
import br.com.reconcile.shared.api.ErrorResponse
import br.com.reconcile.shared.api.ForbiddenException
import br.com.reconcile.shared.api.GlobalExceptionHandler
import br.com.reconcile.shared.api.HealthController
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditLogRepository
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.config.DomainConfig
import br.com.reconcile.shared.domain.Money
import br.com.reconcile.shared.security.JwtAuthFilter
import br.com.reconcile.shared.security.JwtService
import br.com.reconcile.shared.security.LoginRateLimiter
import br.com.reconcile.shared.security.currentUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedSupportTest {
    @AfterEach
    fun cleanup() {
        TestAuth.logout()
    }

    @Test
    fun `money scales values`() {
        assertEquals(BigDecimal("10.00"), Money.of("10"))
        assertEquals(BigDecimal("10.50"), Money.of(10.5))
        assertEquals(BigDecimal("0.00"), Money.ZERO)
        assertEquals(BigDecimal("1.23"), Money.scale(BigDecimal("1.234")))
    }

    @Test
    fun `matching engine covers exact and score paths`() {
        val engine = MatchingEngine()
        assertEquals("matched", engine.matchExact(MatchingLayer.ORDER_PAYMENT, "a", "a").result)
        assertEquals("unmatched", engine.matchExact(MatchingLayer.ORDER_PAYMENT, null, "a").result)
        assertEquals("review", engine.score(MatchingLayer.PAYMENT_SETTLEMENT, true, true, false).result)
        assertEquals("unmatched", engine.score(MatchingLayer.PAYMENT_SETTLEMENT, false, false, true).result)
    }

    @Test
    fun `exception handler maps known failures`() {
        val handler = GlobalExceptionHandler()
        assertEquals(HttpStatus.NOT_FOUND, handler.handleApi(NotFoundException("x")).statusCode)
        assertEquals(HttpStatus.CONFLICT, handler.handleApi(ConflictException("x")).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, handler.handleApi(ForbiddenException("x")).statusCode)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, handler.handleApi(UnprocessableException("x")).statusCode)
        assertEquals("CONFLICT", handler.handleDomain(IllegalCaseTransitionException("bad")).body?.code)
        assertEquals("CONFLICT", handler.handleDomain(IllegalClosingTransitionException("bad")).body?.code)
        assertEquals(HttpStatus.UNAUTHORIZED, handler.handleAuth().statusCode)
        assertEquals(HttpStatus.FORBIDDEN, handler.handleDenied().statusCode)
        assertEquals("VALIDATION", handler.handleValidation().body?.code)
        assertEquals(HttpStatus.UNAUTHORIZED, handler.handleIllegalState(IllegalStateException("off")).statusCode)
        assertEquals("ERROR", handler.handleGeneric().body?.code)
        assertEquals("RATE_LIMIT", handler.handleApi(ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT", "wait")).body?.code)
        BadCredentialsException("x")
        AccessDeniedException("x")
        ErrorResponse("A", "B")
    }

    @Test
    fun `jwt issues and rejects bad secrets`() {
        val jwt = JwtService("unit-test-secret-key-32-bytes-min!!", 1)
        val token = jwt.issue(TestAuth.user)
        val parsed = jwt.parse(token)
        assertEquals(TestAuth.user.email, parsed.email)
        assertThrows<IllegalStateException> { JwtService("short", 1) }
        assertThrows<IllegalStateException> {
            JwtService("change-me-in-production-use-256-bit-secret-key!!", 1)
        }
    }

    @Test
    fun `jwt filter handles missing, invalid and tenant mismatch`() {
        val jwt = JwtService("unit-test-secret-key-32-bytes-min!!", 1)
        val filter = JwtAuthFilter(jwt)
        val chain = MockFilterChain()

        val empty = MockHttpServletRequest()
        empty.addHeader("Authorization", "Bearer ")
        val emptyRes = MockHttpServletResponse()
        filter.doFilter(empty, emptyRes, chain)
        assertEquals(401, emptyRes.status)

        val bad = MockHttpServletRequest()
        bad.addHeader("Authorization", "Bearer not-a-token")
        val badRes = MockHttpServletResponse()
        filter.doFilter(bad, badRes, chain)
        assertEquals(401, badRes.status)

        val ok = MockHttpServletRequest()
        ok.addHeader("Authorization", "Bearer ${jwt.issue(TestAuth.user)}")
        ok.addHeader("X-Tenant-Id", "outro")
        val forbidden = MockHttpServletResponse()
        filter.doFilter(ok, forbidden, chain)
        assertEquals(403, forbidden.status)

        val match = MockHttpServletRequest()
        match.addHeader("Authorization", "Bearer ${jwt.issue(TestAuth.user)}")
        match.addHeader("X-Tenant-Id", "ten_8f2c1a")
        val okRes = MockHttpServletResponse()
        filter.doFilter(match, okRes, MockFilterChain())
        assertEquals(200, okRes.status)
    }

    @Test
    fun `rate limiter trips after five attempts`() {
        val limiter = LoginRateLimiter()
        repeat(5) { limiter.check("ip") }
        assertThrows<ApiException> { limiter.check("ip") }
        limiter.check("other")
    }

    @Test
    fun `current user, health, fiscal stub and audit`() {
        assertThrows<IllegalStateException> { currentUser() }
        TestAuth.login()
        assertEquals("Ana Dantas", currentUser().displayName)
        assertEquals("UP", HealthController().health()["status"])
        assertEquals("pendente", StubFiscalAdapter().consultStatus(null))
        assertEquals("autorizada", StubFiscalAdapter().consultStatus("key"))
        assertTrue(DomainConfig().fiscalAdapter() is StubFiscalAdapter)
        val logs = Mockito.mock(AuditLogRepository::class.java)
        AuditService(logs).record(TestAuth.tenantId, "Ana", "act", "case", "1")
        Mockito.verify(logs).save(Mockito.any() ?: br.com.reconcile.shared.audit.AuditLogEntity())
    }
}
