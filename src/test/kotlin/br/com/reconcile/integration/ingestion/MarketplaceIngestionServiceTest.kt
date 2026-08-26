package br.com.reconcile.integration.ingestion

import br.com.reconcile.TestAuth
import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.integration.oauth.TokenLease
import br.com.reconcile.shared.api.UnprocessableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class MarketplaceIngestionServiceTest {
    private val catalog = Mockito.mock(MarketplaceCatalog::class.java)
    private val writer = Mockito.mock(LedgerIngestionWriter::class.java)
    private val oauth = Mockito.mock(MarketplaceOAuthService::class.java)
    private val connections = Mockito.mock(ConnectionRepository::class.java)

    @Test
    fun `pulls catalog and persists canonical sales`() {
        Mockito.`when`(catalog.providerId()).thenReturn("ml")
        val service = MarketplaceIngestionService(MarketplaceCatalogRegistry(listOf(catalog)), writer, oauth, connections)
        val expires = Instant.now().plusSeconds(3600)
        val connection = ConnectionEntity(
            tenantId = TestAuth.tenantId,
            externalId = "ml",
            name = "Mercado Livre",
            status = "ativa",
            accessToken = "tok",
            refreshToken = "ref",
            sellerExternalId = "195831534",
            tokenExpiresAt = expires,
        )
        Mockito.`when`(oauth.ensureAccessToken("ml", "tok", "ref", expires, "195831534"))
            .thenReturn(TokenLease("tok", "ref", expires, rotated = false))
        val sale = CanonicalSale(
            order = CanonicalOrder(
                externalId = "1",
                orderedAt = Instant.parse("2026-08-04T18:00:00Z"),
                buyerName = "Ana",
                marketplace = "Mercado Livre",
                items = 1,
                grossAmount = BigDecimal("10.00"),
                expectedNet = BigDecimal("9.00"),
                actualNet = BigDecimal("9.00"),
                status = "pago",
            ),
            payments = emptyList(),
            fees = emptyList(),
            refunds = emptyList(),
            invoice = null,
        )
        Mockito.`when`(catalog.pull(any() ?: credentials(), any() ?: IngestionWindow.lastDays(1)))
            .thenReturn(CatalogSnapshot(listOf(sale)))
        Mockito.`when`(writer.persist(TestAuth.tenantId, listOf(sale))).thenReturn(IngestionResult(1, 0, 0, 0, 0))
        assertEquals(1, service.ingest(connection).orders)
        Mockito.verify(connections).save(connection)
    }

    @Test
    fun `rejects disconnected marketplace`() {
        Mockito.`when`(catalog.providerId()).thenReturn("ml")
        val service = MarketplaceIngestionService(MarketplaceCatalogRegistry(listOf(catalog)), writer, oauth, connections)
        val connection = ConnectionEntity(externalId = "ml", name = "Mercado Livre", status = "disponivel")
        assertThrows<UnprocessableException> { service.ingest(connection) }
    }

    private fun credentials() = CatalogCredentials(TestAuth.tenantId, "195831534", "tok", "ref", null)
}
