package br.com.reconcile.integration.ingestion.amazon

import br.com.reconcile.integration.ingestion.CatalogCredentials
import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.integration.ingestion.CatalogSnapshot
import br.com.reconcile.integration.ingestion.IngestionWindow
import br.com.reconcile.integration.ingestion.MarketplaceCatalog
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.integration.oauth.ProviderProperties
import br.com.reconcile.shared.api.UnprocessableException
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class AmazonCatalog(
    private val http: JsonHttpClient,
    private val mapper: AmazonSaleMapper,
    private val properties: MarketplaceOAuthProperties,
) : MarketplaceCatalog {
    override fun providerId() = "amazon"

    override fun pull(credentials: CatalogCredentials, window: IngestionWindow): CatalogSnapshot {
        val config = properties.amazon
        requireConfigured(config)
        val marketplaceId = config.marketplaceId.ifBlank { BRAZIL_MARKETPLACE }
        val createdBefore = createdBefore(window.to)
        val sales = mutableListOf<br.com.reconcile.integration.ingestion.CanonicalSale>()
        var nextToken = ""
        var page = 0
        while (page < 20) {
            val builder = UriComponentsBuilder.fromUriString(config.apiUrl.trimEnd('/') + "/orders/v0/orders")
                .queryParam("MarketplaceIds", marketplaceId)
                .queryParam("CreatedAfter", window.from.toString())
                .queryParam("CreatedBefore", createdBefore.toString())
            if (nextToken.isNotBlank()) builder.queryParam("NextToken", nextToken)
            val listUrl = builder.build().encode().toUri()
            val list = http.getAmazonJson(listUrl.toString(), signedHeaders(config, listUrl, credentials.accessToken))
            val payload = CatalogJson.child(list, "payload").ifEmpty { list }
            CatalogJson.maps(payload["Orders"]).forEach { order ->
                val id = CatalogJson.text(order, "AmazonOrderId") ?: return@forEach
                if (!ORDER_ID.matches(id)) return@forEach
                val items = orderItems(config, credentials.accessToken, id)
                mapper.toSale(order, items)?.let(sales::add)
            }
            nextToken = CatalogJson.text(payload, "NextToken").orEmpty()
            page++
            if (nextToken.isBlank()) break
        }
        return CatalogSnapshot(sales)
    }

    private fun orderItems(config: ProviderProperties, accessToken: String, orderId: String): List<Map<String, Any?>> {
        val uri = UriComponentsBuilder.fromUriString(config.apiUrl.trimEnd('/'))
            .pathSegment("orders", "v0", "orders", orderId, "orderItems")
            .build()
            .encode()
            .toUri()
        val body = http.getAmazonJson(uri.toString(), signedHeaders(config, uri, accessToken))
        val payload = CatalogJson.child(body, "payload").ifEmpty { body }
        return CatalogJson.maps(payload["OrderItems"])
    }

    private fun signedHeaders(config: ProviderProperties, uri: URI, accessToken: String) =
        AmazonRequestSigner.headers(
            method = "GET",
            uri = uri,
            lwaAccessToken = accessToken,
            awsAccessKey = config.awsAccessKey,
            awsSecretKey = config.awsSecretKey,
            region = config.awsRegion.ifBlank { "us-east-1" },
            sessionToken = config.awsSessionToken.ifBlank { null },
            now = Instant.now(),
        )

    private fun requireConfigured(config: ProviderProperties) {
        if (config.clientId.isBlank() || config.clientSecret.isBlank()) {
            throw UnprocessableException("Defina AMAZON_CLIENT_ID e AMAZON_CLIENT_SECRET no .env")
        }
        if (config.awsAccessKey.isBlank() || config.awsSecretKey.isBlank()) {
            throw UnprocessableException("Defina AMAZON_AWS_ACCESS_KEY e AMAZON_AWS_SECRET_KEY no .env")
        }
    }

    companion object {
        const val BRAZIL_MARKETPLACE = "A2Q3Y263D00KWC"
        val ORDER_ID = Regex("""\d{3}-\d{7}-\d{7}""")

        fun createdBefore(requestedTo: Instant, now: Instant = Instant.now()): Instant {
            val limit = now.minus(3, ChronoUnit.MINUTES)
            return if (requestedTo.isAfter(limit)) limit else requestedTo
        }
    }
}
