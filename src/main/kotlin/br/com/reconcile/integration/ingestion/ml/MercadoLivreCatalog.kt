package br.com.reconcile.integration.ingestion.ml

import br.com.reconcile.integration.ingestion.CatalogCredentials
import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.integration.ingestion.CatalogSnapshot
import br.com.reconcile.integration.ingestion.IngestionWindow
import br.com.reconcile.integration.ingestion.MarketplaceCatalog
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.shared.api.UnprocessableException
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

@Component
class MercadoLivreCatalog(
    private val http: JsonHttpClient,
    private val mapper: MercadoLivreSaleMapper,
    private val properties: MarketplaceOAuthProperties,
) : MarketplaceCatalog {
    override fun providerId() = "ml"

    override fun pull(credentials: CatalogCredentials, window: IngestionWindow): CatalogSnapshot {
        val seller = credentials.sellerExternalId.ifBlank {
            throw UnprocessableException("Seller do Mercado Livre ausente. Reconecte a integração.")
        }
        val base = properties.mercadoLivre.apiUrl.trimEnd('/')
        val sales = mutableListOf<br.com.reconcile.integration.ingestion.CanonicalSale>()
        var offset = 0
        val limit = 50
        do {
            val url = UriComponentsBuilder.fromUriString("$base/orders/search")
                .queryParam("seller", seller)
                .queryParam("order.date_created.from", format(window.from))
                .queryParam("order.date_created.to", format(window.to))
                .queryParam("sort", "date_desc")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()
                .encode()
                .toUriString()
            val page = http.getJson(url, credentials.accessToken)
            val results = CatalogJson.maps(page["results"])
            results.forEach { order ->
                mapper.toSale(order, fiscalOf(base, credentials.accessToken, order))?.let(sales::add)
            }
            val total = CatalogJson.intValue(CatalogJson.child(page, "paging"), "total", results.size)
            offset += limit
            if (results.isEmpty() || offset >= total || offset >= 1000) break
        } while (true)
        return CatalogSnapshot(sales)
    }

    private fun fiscalOf(base: String, token: String, order: Map<String, Any?>): Map<String, Any?> {
        val shippingId = CatalogJson.text(CatalogJson.child(order, "shipping"), "id")
        if (!shippingId.isNullOrBlank()) {
            val info = http.getJsonOrEmpty("$base/shipments/$shippingId/fiscal-info", token)
            if (info.isNotEmpty()) return info
        }
        val packId = CatalogJson.text(order, "pack_id") ?: CatalogJson.text(order, "id") ?: return emptyMap()
        return http.getJsonOrEmpty("$base/packs/$packId/fiscal_documents", token)
    }

    private fun format(instant: java.time.Instant): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))
}
