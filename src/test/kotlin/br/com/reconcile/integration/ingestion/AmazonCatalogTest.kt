package br.com.reconcile.integration.ingestion

import br.com.reconcile.integration.ingestion.amazon.AmazonCatalog
import br.com.reconcile.integration.ingestion.amazon.AmazonRequestSigner
import br.com.reconcile.integration.ingestion.amazon.AmazonSaleMapper
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.shared.api.UnprocessableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmazonCatalogTest {
    private val calls = mutableListOf<Pair<String, Map<String, String>>>()
    private val http = object : JsonHttpClient {
        override fun postForm(url: String, form: Map<String, String>) = emptyMap<String, Any?>()
        override fun postJson(url: String, body: Map<String, Any?>) = emptyMap<String, Any?>()
        override fun getJson(url: String, bearer: String) = emptyMap<String, Any?>()
        override fun getJsonOrEmpty(url: String, bearer: String) = emptyMap<String, Any?>()
        override fun getAmazonJson(url: String, headers: Map<String, String>): Map<String, Any?> {
            calls += url to headers
            return if (url.contains("orderItems")) {
                mapOf("payload" to mapOf("OrderItems" to listOf(mapOf("QuantityOrdered" to 1, "ItemPrice" to mapOf("Amount" to "100.00")))))
            } else {
                mapOf(
                    "payload" to mapOf(
                        "Orders" to listOf(
                            mapOf(
                                "AmazonOrderId" to "702-0000001-0000001",
                                "PurchaseDate" to "2026-05-06T15:10:00Z",
                                "OrderStatus" to "Shipped",
                                "OrderTotal" to mapOf("Amount" to "100.00"),
                            ),
                            mapOf("AmazonOrderId" to "../admin"),
                        ),
                    ),
                )
            }
        }

        override fun getAmazonJsonOrEmpty(url: String, headers: Map<String, String>): Map<String, Any?> {
            calls += url to headers
            return mapOf(
                "payload" to mapOf(
                    "FinancialEvents" to mapOf(
                        "ShipmentEventList" to listOf(
                            mapOf(
                                "ShipmentItemList" to listOf(
                                    mapOf(
                                        "ItemFeeList" to listOf(
                                            mapOf("FeeType" to "Commission", "FeeAmount" to mapOf("CurrencyAmount" to -10.0)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }
    private val properties = MarketplaceOAuthProperties().apply {
        amazon.apply {
            clientId = "amzn1.application.demo"
            clientSecret = "amz-secret"
            apiUrl = "https://sellingpartnerapi-na.amazon.com"
            awsAccessKey = "AKIADEMO"
            awsSecretKey = "aws-secret"
            awsRegion = "us-east-1"
            marketplaceId = AmazonCatalog.BRAZIL_MARKETPLACE
        }
    }
    private val catalog = AmazonCatalog(http, AmazonSaleMapper(), properties)

    @Test
    fun `pulls brazil orders with a signed request and skips unsafe ids`() {
        val now = Instant.now()
        val window = IngestionWindow(now.minus(1, ChronoUnit.DAYS), now)
        val snapshot = catalog.pull(
            CatalogCredentials(UUID.randomUUID(), "A1AMZDEMO204918", "Atza|token", null, null),
            window,
        )
        assertEquals(1, snapshot.sales.size)
        assertEquals("amazon", catalog.providerId())
        assertEquals("Amazon", snapshot.sales.single().order.marketplace)
        val list = calls.first()
        val decoded = java.net.URLDecoder.decode(list.first, Charsets.UTF_8)
        assertTrue(decoded.contains("MarketplaceIds=${AmazonCatalog.BRAZIL_MARKETPLACE}"))
        val createdBefore = decoded.substringAfter("CreatedBefore=").substringBefore("&")
        val bound = Instant.parse(createdBefore)
        assertTrue(!bound.isAfter(now.minus(2, ChronoUnit.MINUTES)))
        assertTrue(list.second["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 "))
        assertEquals("Atza|token", list.second["x-amz-access-token"])
        assertTrue(calls.none { it.first.contains("../") || it.first.contains("admin") })
        assertTrue(calls.any { it.first.contains("/orders/v0/orders/702-0000001-0000001/orderItems") })
        assertTrue(calls.any { it.first.contains("/finances/v0/orders/702-0000001-0000001/financialEvents") })
        assertEquals(java.math.BigDecimal("90.00"), snapshot.sales.single().order.actualNet)
    }

    @Test
    fun `rejects a pull without aws credentials`() {
        val previous = properties.amazon.awsSecretKey
        properties.amazon.awsSecretKey = ""
        try {
            assertThrows<UnprocessableException> {
                catalog.pull(
                    CatalogCredentials(UUID.randomUUID(), "A1", "tok", null, null),
                    IngestionWindow.lastDays(1),
                )
            }
        } finally {
            properties.amazon.awsSecretKey = previous
        }
        properties.amazon.clientId = ""
        try {
            assertThrows<UnprocessableException> {
                catalog.pull(
                    CatalogCredentials(UUID.randomUUID(), "A1", "tok", null, null),
                    IngestionWindow.lastDays(1),
                )
            }
        } finally {
            properties.amazon.clientId = "amzn1.application.demo"
        }
    }

    @Test
    fun `clamps created before to three minutes ago`() {
        val now = Instant.parse("2026-09-24T12:00:00Z")
        assertEquals(now.minus(3, ChronoUnit.MINUTES), AmazonCatalog.createdBefore(now, now))
        val older = now.minus(2, ChronoUnit.HOURS)
        assertEquals(older, AmazonCatalog.createdBefore(older, now))
    }

    @Test
    fun `signs the same request twice with the same signature`() {
        val uri = URI("https://sellingpartnerapi-na.amazon.com/orders/v0/orders?MarketplaceIds=A2Q3Y263D00KWC")
        val now = Instant.parse("2026-09-24T12:00:00Z")
        val first = AmazonRequestSigner.headers("GET", uri, "Atza|token", "AKIADEMO", "aws-secret", "us-east-1", null, now)
        val second = AmazonRequestSigner.headers("GET", uri, "Atza|token", "AKIADEMO", "aws-secret", "us-east-1", null, now)
        assertEquals(first["Authorization"], second["Authorization"])
        assertTrue(first["Authorization"]!!.contains("SignedHeaders=host;x-amz-access-token;x-amz-date"))
        val withSession = AmazonRequestSigner.headers(
            "GET", uri, "Atza|token", "AKIADEMO", "aws-secret", "us-east-1", "session-token", now,
        )
        assertTrue(withSession["Authorization"]!!.contains("x-amz-security-token"))
        assertEquals("session-token", withSession["x-amz-security-token"])
    }
}
