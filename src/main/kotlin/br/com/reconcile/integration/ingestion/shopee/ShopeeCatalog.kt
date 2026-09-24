package br.com.reconcile.integration.ingestion.shopee

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
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class ShopeeCatalog(
    private val http: JsonHttpClient,
    private val mapper: ShopeeSaleMapper,
    private val properties: MarketplaceOAuthProperties,
) : MarketplaceCatalog {
    override fun providerId() = "shopee"

    override fun pull(credentials: CatalogCredentials, window: IngestionWindow): CatalogSnapshot {
        val config = properties.shopee
        if (config.clientId.isBlank() || config.clientSecret.isBlank()) {
            throw UnprocessableException("Defina SHOPEE_PARTNER_ID e SHOPEE_PARTNER_KEY no .env")
        }
        val shopId = shopId(credentials.sellerExternalId)
        val sales = mutableListOf<br.com.reconcile.integration.ingestion.CanonicalSale>()
        for (slice in slices(window)) {
            sales += pullSlice(credentials.accessToken, shopId, slice)
        }
        return CatalogSnapshot(sales)
    }

    private fun pullSlice(accessToken: String, shopId: String, window: IngestionWindow): List<br.com.reconcile.integration.ingestion.CanonicalSale> {
        val sales = mutableListOf<br.com.reconcile.integration.ingestion.CanonicalSale>()
        var cursor = ""
        var page = 0
        while (page < 20) {
            val extra = mutableMapOf(
                "time_range_field" to "create_time",
                "time_from" to window.from.epochSecond.toString(),
                "time_to" to window.to.epochSecond.toString(),
                "page_size" to "50",
            )
            if (cursor.isNotBlank()) extra["cursor"] = cursor
            val list = signedGet("/api/v2/order/get_order_list", accessToken, shopId, extra)
            if (list["error"]?.toString()?.isNotBlank() == true) {
                throw UnprocessableException(list["message"]?.toString() ?: "Falha ao listar pedidos Shopee")
            }
            val response = CatalogJson.child(list, "response")
            val numbers = CatalogJson.maps(response["order_list"]).mapNotNull { CatalogJson.text(it, "order_sn") }
            if (numbers.isNotEmpty()) {
                val detail = signedGet(
                    "/api/v2/order/get_order_detail",
                    accessToken,
                    shopId,
                    mapOf(
                        "order_sn_list" to numbers.joinToString(","),
                        "response_optional_fields" to "buyer_username,item_list,total_amount,payment_method,pay_time",
                    ),
                )
                CatalogJson.maps(CatalogJson.child(detail, "response")["order_list"]).forEach { order ->
                    mapper.toSale(withEscrow(accessToken, shopId, order))?.let(sales::add)
                }
            }
            cursor = CatalogJson.text(response, "next_cursor").orEmpty()
            page++
            if (cursor.isBlank() || response["more"] == false) break
        }
        return sales
    }

    private fun withEscrow(accessToken: String, shopId: String, order: Map<String, Any?>): Map<String, Any?> {
        val orderSn = CatalogJson.text(order, "order_sn") ?: return order
        val escrow = signedGet(
            "/api/v2/payment/get_escrow_detail",
            accessToken,
            shopId,
            mapOf("order_sn" to orderSn),
            optional = true,
        )
        if (escrow["error"]?.toString()?.isNotBlank() == true) return order
        val income = CatalogJson.child(CatalogJson.child(escrow, "response"), "order_income")
        if (income.isEmpty()) return order
        return order + ("escrow_detail" to income)
    }

    private fun shopId(sellerExternalId: String): String {
        val raw = sellerExternalId.trim()
        val shop = if (raw.startsWith("m:")) raw.substringAfterLast(':') else raw
        if (raw.startsWith("main:") || shop.toLongOrNull() == null) {
            throw UnprocessableException("Shop da Shopee ausente. Reconecte a integração.")
        }
        return shop
    }

    private fun slices(window: IngestionWindow): List<IngestionWindow> {
        val max = Duration.ofDays(15)
        if (!window.from.isBefore(window.to)) return listOf(window)
        val slices = mutableListOf<IngestionWindow>()
        var cursor = window.from
        while (!cursor.isAfter(window.to)) {
            val end = minOf(cursor.plus(max), window.to)
            slices.add(IngestionWindow(cursor, end))
            if (!end.isBefore(window.to)) break
            cursor = end.plusSeconds(1)
        }
        return slices
    }

    private fun signedGet(
        path: String,
        accessToken: String,
        shopId: String,
        extra: Map<String, String>,
        optional: Boolean = false,
    ): Map<String, Any?> {
        val config = properties.shopee
        val timestamp = Instant.now().epochSecond
        val sign = hmacHex(config.clientSecret, "${config.clientId}$path$timestamp$accessToken$shopId")
        val builder = UriComponentsBuilder.fromUriString(config.apiUrl.trimEnd('/') + path)
            .queryParam("partner_id", config.clientId)
            .queryParam("timestamp", timestamp)
            .queryParam("sign", sign)
            .queryParam("access_token", accessToken)
            .queryParam("shop_id", shopId)
        extra.filterValues { it.isNotBlank() }.forEach { (key, value) -> builder.queryParam(key, value) }
        val url = builder.build().encode().toUriString()
        return if (optional) http.getJsonOrEmpty(url, accessToken) else http.getJson(url, accessToken)
    }

    private fun hmacHex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
