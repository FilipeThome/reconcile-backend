package br.com.reconcile.integration.stocksync

import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signed shop-level calls of Shopee Open Platform v2. */
@Component
class ShopeeApi(
    private val http: JsonHttpClient,
    private val properties: MarketplaceOAuthProperties,
) {
    fun get(access: ConnectionAccess, path: String, params: Map<String, String>): Map<String, Any?> =
        call("GET", access, path, params, null)

    fun post(access: ConnectionAccess, path: String, body: Map<String, Any?>): Map<String, Any?> =
        call("POST", access, path, emptyMap(), body)

    /** Push authentication: Authorization = hex HMAC-SHA256("<callback url>|<raw body>", partner key). */
    fun validPush(authorization: String?, rawBody: String): Boolean {
        val config = properties.shopee
        val url = config.webhookUrl.trim()
        if (url.isEmpty() || config.clientSecret.isBlank() || authorization.isNullOrBlank()) return false
        val expected = hmacHex(config.clientSecret, "$url|$rawBody")
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            authorization.trim().lowercase().toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun shopId(sellerExternalId: String): String {
        val raw = sellerExternalId.trim()
        val shop = if (raw.startsWith("m:")) raw.substringAfterLast(':') else raw
        if (shop.toLongOrNull() == null) throw UnprocessableException(apiMessage("oauth.shopeeShopMissing"))
        return shop
    }

    private fun call(
        method: String,
        access: ConnectionAccess,
        path: String,
        params: Map<String, String>,
        body: Map<String, Any?>?,
    ): Map<String, Any?> {
        val config = properties.shopee
        if (config.clientId.isBlank() || config.clientSecret.isBlank()) {
            throw UnprocessableException(apiMessage("shopee.env"))
        }
        val shop = shopId(access.sellerId)
        val timestamp = Instant.now().epochSecond
        val sign = hmacHex(config.clientSecret, "${config.clientId}$path$timestamp${access.token}$shop")
        val builder = UriComponentsBuilder.fromUriString(config.apiUrl.trimEnd('/') + path)
            .queryParam("partner_id", config.clientId)
            .queryParam("timestamp", timestamp)
            .queryParam("sign", sign)
            .queryParam("access_token", access.token)
            .queryParam("shop_id", shop)
        params.forEach { (key, value) -> builder.queryParam(key, value) }
        val response = http.requestJson(method, builder.build().encode().toUriString(), access.token, body)
        val payload = CatalogJson.map(response.body)
        val error = CatalogJson.text(payload, "error")
        if (response.status >= 400 || error != null) {
            val detail = CatalogJson.text(payload, "message") ?: error ?: response.status.toString()
            throw UnprocessableException(apiMessage("stocksync.shopee", detail))
        }
        return CatalogJson.child(payload, "response")
    }

    private fun hmacHex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

@Component
class ShopeeStockPort(private val api: ShopeeApi) : MarketplaceStockPort {
    override fun providerId() = "shopee"

    override fun find(access: ConnectionAccess, sku: String): List<StockListing> {
        val clean = sku.trim()
        val search = api.get(access, "/api/v2/product/search_item", mapOf("item_sku" to clean, "page_size" to "50"))
        val ids = (search["item_id_list"] as? List<*>).orEmpty().mapNotNull { it?.toString() }.take(50)
        if (ids.isEmpty()) return emptyList()
        val base = api.get(access, "/api/v2/product/get_item_base_info", mapOf("item_id_list" to ids.joinToString(",")))
        return CatalogJson.maps(base["item_list"]).flatMap { item ->
            val itemId = CatalogJson.text(item, "item_id") ?: return@flatMap emptyList()
            val title = CatalogJson.text(item, "item_name") ?: itemId
            if (item["has_model"] == true) {
                val models = api.get(access, "/api/v2/product/get_model_list", mapOf("item_id" to itemId))
                CatalogJson.maps(models["model"])
                    .filter { CatalogJson.text(it, "model_sku").equals(clean, ignoreCase = true) }
                    .mapNotNull { model ->
                        val modelId = CatalogJson.text(model, "model_id") ?: return@mapNotNull null
                        listing(itemId, modelId, title, CatalogJson.child(model, "stock_info_v2"))
                    }
            } else if (CatalogJson.text(item, "item_sku").equals(clean, ignoreCase = true)) {
                listOf(listing(itemId, null, title, CatalogJson.child(item, "stock_info_v2")))
            } else {
                emptyList()
            }
        }
    }

    override fun setQuantity(access: ConnectionAccess, listing: StockListing, quantity: Int) {
        val stock = mutableMapOf<String, Any?>("stock" to quantity)
        listing.locationId?.let { stock["location_id"] = it }
        val response = api.post(
            access,
            "/api/v2/product/update_stock",
            mapOf(
                "item_id" to listing.itemId.toLong(),
                "stock_list" to listOf(
                    mapOf("model_id" to (listing.variationId?.toLong() ?: 0L), "seller_stock" to listOf(stock)),
                ),
            ),
        )
        CatalogJson.maps(response["failure_list"]).firstOrNull()?.let { failure ->
            throw UnprocessableException(
                apiMessage("stocksync.shopee", CatalogJson.text(failure, "failed_reason") ?: "update_stock"),
            )
        }
    }

    /** Seller stock of a single warehouse. Several warehouses cannot be changed by a plain delta. */
    private fun listing(itemId: String, modelId: String?, title: String, stockInfo: Map<String, Any?>): StockListing {
        val seller = CatalogJson.maps(stockInfo["seller_stock"])
        val single = seller.singleOrNull()
        return StockListing(
            itemId = itemId,
            variationId = modelId,
            title = title,
            quantity = if (single != null && single["stock"] is Number) CatalogJson.intValue(single, "stock") else null,
            locationId = single?.let { CatalogJson.text(it, "location_id") },
        )
    }
}
