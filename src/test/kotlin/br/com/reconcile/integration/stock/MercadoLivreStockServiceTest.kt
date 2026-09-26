package br.com.reconcile.integration.stock

import br.com.reconcile.TestAuth
import br.com.reconcile.integration.api.MercadoLivreStockController
import br.com.reconcile.integration.api.UpdateStockRequest
import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.integration.oauth.TokenLease
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MercadoLivreStockServiceTest {
    private val connections = Mockito.mock(ConnectionRepository::class.java)
    private val oauth = Mockito.mock(MarketplaceOAuthService::class.java)
    private val client = Mockito.mock(MercadoLivreStockClient::class.java)
    private val audit = Mockito.mock(AuditService::class.java)
    private val tokens = Mockito.mock(StockConnectionTokenWriter::class.java)
    private val service = MercadoLivreStockService(connections, oauth, client, audit, tokens)
    private val connection = ConnectionEntity(
        tenantId = TestAuth.tenantId,
        externalId = "ml",
        name = "Mercado Livre",
        status = "ativa",
        accessToken = "tok",
        refreshToken = "ref",
        sellerExternalId = "99",
    )

    @BeforeEach
    fun setUp() {
        TestAuth.login()
        Mockito.`when`(connections.findByTenantIdAndExternalId(TestAuth.tenantId, "ml")).thenReturn(connection)
        Mockito.`when`(
            oauth.ensureAccessToken(
                anyString(),
                anyString(),
                nullable(String::class.java),
                nullable(Instant::class.java),
                nullable(String::class.java),
            ),
        ).thenReturn(TokenLease("access", "ref", null, false))
        Mockito.`when`(client.sellerId(anyString(), anyString())).thenReturn("99")
        Mockito.`when`(
            client.list(
                anyString(),
                anyString(),
                nullable(String::class.java),
                nullable(String::class.java),
                anyInt(),
                anyInt(),
            ),
        ).thenReturn(StockPage(0, 0, 20, emptyList()))
    }

    @AfterEach
    fun tearDown() {
        TestAuth.logout()
    }

    @Test
    fun `list normalizes filters and reuses the oauth token`() {
        service.list("  SKU-1  ", " ACTIVE ", -4, 100)
        Mockito.verify(client).list("access", "99", "SKU-1", "active", 0, 20)

        service.list(" ", "all", 2000, 0)
        Mockito.verify(client).list("access", "99", null, null, 1000, 1)

        service.list(null, " under_review ", 0, 20)
        Mockito.verify(client).list("access", "99", null, "under_review", 0, 20)
        service.list(null, "paused", 0, 20)
        service.list(null, "closed", 0, 20)
        service.list(null, "inactive", 0, 20)
        service.list(null, "", 0, 20)

        val invalid = assertThrows<UnprocessableException> { service.list(null, "nope", 0, 20) }
        assertTrue(invalid.message!!.contains("Status"))
    }

    @Test
    fun `get and update use the connected seller and audit the change`() {
        val item = StockItem("MLB10000001", "Camiseta", "CAM", "active", 6, 1, null, null, true, emptyList(), emptyList())
        Mockito.`when`(client.get("access", "99", "MLB10000001")).thenReturn(item)
        assertSame(item, service.get("MLB10000001"))

        Mockito.`when`(client.update("access", "99", "MLB10000001", 6, 11L, "A1")).thenReturn(item)
        assertSame(item, service.update("MLB10000001", 6, 11L, " A1 "))
        Mockito.verify(audit).record(
            TestAuth.tenantId,
            "Ana Dantas",
            "integration.stock.update",
            "integration",
            "MLB10000001",
            """{"quantity":6,"variationId":11,"storeId":"A1","networkNodeId":null}""",
        )

        Mockito.`when`(client.update("access", "99", "MLB10000001", 1, null, null, "N1")).thenReturn(item)
        service.update("MLB10000001", 1, null, " ", " N1 ")
        Mockito.verify(audit).record(
            TestAuth.tenantId,
            "Ana Dantas",
            "integration.stock.update",
            "integration",
            "MLB10000001",
            """{"quantity":1,"variationId":null,"storeId":null,"networkNodeId":"N1"}""",
        )
    }

    @Test
    fun `stock requires an active mercado livre connection and refreshes expired tokens`() {
        Mockito.`when`(connections.findByTenantIdAndExternalId(TestAuth.tenantId, "ml")).thenReturn(null)
        assertThrows<NotFoundException> { service.list(null, null, 0, 20) }

        Mockito.`when`(connections.findByTenantIdAndExternalId(TestAuth.tenantId, "ml")).thenReturn(connection)
        connection.status = "disponivel"
        assertThrows<UnprocessableException> { service.get("MLB10000001") }

        connection.status = "ativa"
        connection.accessToken = null
        assertThrows<UnprocessableException> { service.list(null, null, 0, 20) }

        connection.accessToken = "tok"
        connection.sellerExternalId = "MLB-204918"
        val expires = Instant.parse("2026-06-01T00:00:00Z")
        Mockito.`when`(
            oauth.ensureAccessToken(
                anyString(),
                anyString(),
                nullable(String::class.java),
                nullable(Instant::class.java),
                nullable(String::class.java),
            ),
        ).thenReturn(TokenLease("new-token", "new-ref", expires, true))
        Mockito.`when`(client.sellerId("new-token", "MLB-204918")).thenReturn("195831534")
        service.list("x".repeat(90), null, 0, 20)
        assertEquals("new-token", connection.accessToken)
        assertEquals("new-ref", connection.refreshToken)
        assertEquals(expires, connection.tokenExpiresAt)
        Mockito.verify(tokens).persist(connection)
        Mockito.verify(client).sellerId("new-token", "MLB-204918")
        Mockito.verify(client).list("new-token", "195831534", "x".repeat(80), null, 0, 20)

        Mockito.`when`(
            client.list(
                anyString(),
                anyString(),
                nullable(String::class.java),
                nullable(String::class.java),
                anyInt(),
                anyInt(),
            ),
        ).thenThrow(UnprocessableException("Falha ao consultar o Mercado Livre"))
        assertThrows<UnprocessableException> { service.list(null, null, 0, 20) }
        Mockito.verify(tokens, Mockito.atLeast(2)).persist(connection)
        assertEquals("new-token", connection.accessToken)
        assertEquals("new-ref", connection.refreshToken)
    }

    @Test
    fun `controller delegates list get and update`() {
        val stock = Mockito.mock(MercadoLivreStockService::class.java)
        val controller = MercadoLivreStockController(stock)
        val page = StockPage(1, 0, 20, emptyList())
        val item = StockItem("MLB10000001", "Camiseta", null, "active", 3, 0, null, null, true, emptyList(), emptyList())
        Mockito.`when`(stock.list("q", "active", 1, 5)).thenReturn(page)
        Mockito.`when`(stock.get("MLB10000001")).thenReturn(item)
        Mockito.`when`(stock.update("MLB10000001", 3, null, null, null)).thenReturn(item)
        Mockito.`when`(stock.update("MLB10000001", 4, 9L, "Z", "N9")).thenReturn(item)
        assertSame(page, controller.list("q", "active", 1, 5))
        assertSame(item, controller.get("MLB10000001"))
        assertSame(item, controller.update("MLB10000001", UpdateStockRequest(3)))
        assertSame(item, controller.update("MLB10000001", UpdateStockRequest(4, 9L, "Z", "N9")))
    }
}
