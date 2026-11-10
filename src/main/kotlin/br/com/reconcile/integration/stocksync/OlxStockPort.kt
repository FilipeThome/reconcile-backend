package br.com.reconcile.integration.stocksync

import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

/**
 * OLX has no stock quantity and no order API: one ad is one item. The ad whose id equals the SKU
 * counts as one unit; reaching zero removes the ad, and only when OLX_DELETE_SOLD_ADS=true.
 */
@Component
class OlxStockPort(
    private val http: JsonHttpClient,
    private val properties: MarketplaceOAuthProperties,
) : MarketplaceStockPort {
    override fun providerId() = "olx"

    override fun tracksQuantity() = false

    override fun find(access: ConnectionAccess, sku: String): List<StockListing> {
        val clean = sku.trim()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val builder = UriComponentsBuilder.fromUriString("${base()}/autoupload/v1/published").queryParam("fetch_size", 200)
            pageToken?.let { builder.queryParam("page_token", it) }
            val response = http.requestJson("GET", builder.build().encode().toUriString(), access.token)
            if (response.status >= 400) throw UnprocessableException(apiMessage("stocksync.olx", response.status))
            val payload = CatalogJson.map(response.body)
            CatalogJson.maps(payload["data"])
                .firstOrNull { CatalogJson.text(it, "id").equals(clean, ignoreCase = true) }
                ?.let { ad ->
                    val id = CatalogJson.text(ad, "id") ?: clean
                    return listOf(StockListing(id, null, CatalogJson.text(ad, "subject") ?: id, 1))
                }
            pageToken = CatalogJson.text(payload, "next_token") ?: return emptyList()
        }
        return emptyList()
    }

    override fun setQuantity(access: ConnectionAccess, listing: StockListing, quantity: Int) {
        if (quantity > 0) return
        if (!properties.olx.deleteSoldAds) throw UnprocessableException(apiMessage("stocksync.olxManual"))
        val response = http.requestJson(
            "PUT",
            "${base()}/autoupload/import",
            access.token,
            mapOf(
                "access_token" to access.token,
                "ad_list" to listOf(mapOf("id" to listing.itemId, "operation" to "delete")),
            ),
        )
        val status = CatalogJson.map(response.body)["statusCode"]
        if (response.status >= 400 || (status is Number && status.toInt() != 0)) {
            throw UnprocessableException(apiMessage("stocksync.olx", status ?: response.status))
        }
    }

    /** Subscribes this account's ad-status notifications. False when OLX_WEBHOOK_URL is not set. */
    fun registerWebhook(accessToken: String, webhookToken: String): Boolean {
        val url = properties.olx.webhookUrl.trim()
        if (url.isEmpty()) return false
        val response = http.requestJson(
            "POST",
            "${base()}/autoservice/v1/notification",
            accessToken,
            mapOf("method" to "POST", "url" to url, "media_type" to "application/json", "token" to webhookToken),
        )
        return response.status < 400
    }

    private fun base() = properties.olx.apiUrl.trimEnd('/')

    private companion object {
        const val MAX_PAGES = 10
    }
}
