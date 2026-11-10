package br.com.reconcile.integration.stocksync

import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.oauth.HttpJson
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.integration.oauth.TokenLease
import br.com.reconcile.integration.stock.MercadoLivreStockClient
import br.com.reconcile.integration.stock.StockConnectionTokenWriter
import br.com.reconcile.shared.api.UnprocessableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted marketplace: the first rule whose fragment is in "METHOD url" answers the call. */
class FakeHttp : JsonHttpClient {
    class Call(val method: String, val url: String, val body: Any?)

    val calls = mutableListOf<Call>()
    private val rules = mutableListOf<Pair<String, (Call) -> HttpJson>>()

    fun on(fragment: String, status: Int = 200, headers: Map<String, String> = emptyMap(), body: Any?) =
        apply { rules.add(fragment to { _: Call -> HttpJson(status, body, headers) }) }

    fun sent(fragment: String) = calls.filter { "${it.method} ${it.url}".contains(fragment) }

    override fun requestJson(method: String, url: String, bearer: String, body: Any?, headers: Map<String, String>): HttpJson {
        val call = Call(method, url, body)
        calls.add(call)
        return rules.firstOrNull { "$method $url".contains(it.first) }?.second?.invoke(call) ?: HttpJson(404, emptyMap<String, Any?>())
    }

    override fun postForm(url: String, form: Map<String, String>) = answer("POST", url, form)
    override fun postJson(url: String, body: Map<String, Any?>) = answer("POST", url, body)
    override fun getJson(url: String, bearer: String) = answer("GET", url, null)
    override fun getJsonOrEmpty(url: String, bearer: String) = answer("GET", url, null)
    override fun getAmazonJson(url: String, headers: Map<String, String>) = answer("GET", url, null)
    override fun getAmazonJsonOrEmpty(url: String, headers: Map<String, String>) = answer("GET", url, null)

    @Suppress("UNCHECKED_CAST")
    private fun answer(method: String, url: String, body: Any?) =
        requestJson(method, url, "", body, emptyMap()).body as? Map<String, Any?> ?: emptyMap()
}

fun stockProperties() = MarketplaceOAuthProperties().apply {
    mercadoLivre.apply {
        clientId = "777"
        clientSecret = "ml-secret"
        apiUrl = "https://api.mercadolibre.com"
    }
    shopee.apply {
        clientId = "10090"
        clientSecret = "partner-key"
        apiUrl = "https://openplatform.shopee.com.br"
        webhookUrl = "https://loja.example/api/v1/integrations/webhooks/shopee"
    }
    olx.apply {
        clientId = "olx-id"
        clientSecret = "olx-secret"
        apiUrl = "https://apps.olx.com.br"
    }
}

fun hmacHex(secret: String, value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class StockPortsTest {
    private val http = FakeHttp()
    private val properties = stockProperties()
    private val ml = ConnectionAccess("ml", "tok", "99")
    private val shop = ConnectionAccess("shopee", "tok", "m:5:44120")
    private val olx = ConnectionAccess("olx", "tok", "loja@example.com")

    private fun mlItem(id: String, sku: String?, quantity: Int, variations: List<Map<String, Any?>> = emptyList()) = mapOf(
        "code" to 200,
        "body" to mapOf(
            "id" to id,
            "title" to "Produto $id",
            "seller_id" to 99,
            "status" to "active",
            "available_quantity" to quantity,
            "seller_custom_field" to sku,
            "variations" to variations,
        ),
    )

    @Test
    fun `mercado livre never matches a sku by title`() {
        http.on("seller_sku=ABC", body = mapOf("results" to emptyList<String>()))
            .on("sku=ABC", body = mapOf("results" to emptyList<String>()))
            .on("q=ABC", body = mapOf("results" to listOf("MLB11111")))
        val port = MercadoLivreStockPort(MercadoLivreStockClient(http, properties))

        assertTrue(port.find(ml, "ABC").isEmpty())
        assertTrue(port.find(ml, "  ").isEmpty())
        assertTrue(http.sent("q=").isEmpty())
        assertTrue(http.sent("PUT").isEmpty())
    }

    @Test
    fun `mercado livre keeps only listings whose sku is equal and updates them`() {
        val variation = mapOf("id" to 7, "available_quantity" to 4, "seller_custom_field" to "abc")
        http.on("seller_sku=abc", body = mapOf("results" to listOf("MLB11111", "MLB22222", "MLB33333")))
            .on("sku=abc", body = mapOf("results" to listOf("MLB11111", "not-an-id")))
            .on(
                "/items/bulk?ids=MLB11111,MLB22222,MLB33333&attributes=body.id,",
                body = listOf(mlItem("MLB11111", "ABC", 9), mlItem("MLB22222", "ABC-2", 3), mlItem("MLB33333", null, 4, listOf(variation))),
            )
            .on("GET https://api.mercadolibre.com/items/MLB11111", body = mlItem("MLB11111", "ABC", 9)["body"])
            .on("PUT https://api.mercadolibre.com/items/MLB11111", body = emptyMap<String, Any?>())
        val port = MercadoLivreStockPort(MercadoLivreStockClient(http, properties))

        val found = port.find(ml, "abc")
        assertEquals(listOf("MLB11111" to null, "MLB33333" to "7"), found.map { it.itemId to it.variationId })
        assertEquals(listOf(9, 4), found.map { it.quantity })

        port.setQuantity(ml, found.first(), 8)
        assertEquals(mapOf("available_quantity" to 8), http.sent("PUT").single().body)

        val refused = FakeHttp().on("seller_sku", status = 500, body = mapOf("message" to "down"))
        assertThrows<UnprocessableException> { MercadoLivreStockClient(refused, properties).findBySku("tok", "99", "ABC") }
    }

    @Test
    fun `mercado livre baseline is the location it writes, and every listing of a sku is loaded`() {
        fun distributed(id: String, product: String) = mlItem(id, "ABC", 9).let { it + ("body" to (it.getValue("body") as Map<*, *> + ("user_product_id" to product))) }
        val ids = (11111..11155).map { "MLB$it" }
        http.on("seller_sku=ABC", body = mapOf("results" to ids))
            .on("sku=ABC", body = mapOf("results" to emptyList<String>()))
            .on("/items/bulk?ids=MLB11111,", body = listOf(distributed("MLB11111", "MLBU10000001"), distributed("MLB11112", "MLBU10000002")))
            .on("/items/bulk?ids=", body = emptyList<Any>())
            .on(
                "/user-products/MLBU10000001/stock",
                body = mapOf("locations" to listOf(mapOf("type" to "selling_address", "quantity" to 3), mapOf("type" to "meli_facility", "quantity" to 6))),
            )
            .on(
                "/user-products/MLBU10000002/stock",
                body = mapOf(
                    "locations" to listOf(
                        mapOf("type" to "seller_warehouse", "store_id" to "A", "network_node_id" to "N1", "quantity" to 4),
                        mapOf("type" to "seller_warehouse", "store_id" to "B", "network_node_id" to "N2", "quantity" to 5),
                    ),
                ),
            )

        val found = MercadoLivreStockPort(MercadoLivreStockClient(http, properties)).find(ml, "ABC")

        assertEquals(listOf<Int?>(3, null), found.map { it.quantity }, "Full units are not the seller's to lower; two warehouses are ambiguous")
        assertEquals(3, http.sent("/items/bulk?ids=").size, "45 listings are read 20 at a time")
        assertTrue(http.sent("seller_sku=ABC").single().url.contains("limit=50"))
    }

    @Test
    fun `shopee finds items and models by exact sku and writes seller stock`() {
        http.on("/product/search_item", body = mapOf("response" to mapOf("item_id_list" to listOf(1, 2, 3))))
            .on(
                "/product/get_item_base_info",
                body = mapOf(
                    "response" to mapOf(
                        "item_list" to listOf(
                            mapOf(
                                "item_id" to 1,
                                "item_name" to "Camisa",
                                "item_sku" to "ABC",
                                "has_model" to false,
                                "stock_info_v2" to mapOf("seller_stock" to listOf(mapOf("location_id" to "BR1", "stock" to 5))),
                            ),
                            mapOf("item_id" to 2, "item_name" to "Outra", "item_sku" to "ABC-XL", "has_model" to false),
                            mapOf("item_id" to 3, "item_name" to "Calça", "has_model" to true),
                            mapOf("item_name" to "sem id"),
                        ),
                    ),
                ),
            )
            .on(
                "/product/get_model_list",
                body = mapOf(
                    "response" to mapOf(
                        "model" to listOf(
                            mapOf("model_id" to 31, "model_sku" to "abc", "stock_info_v2" to mapOf("seller_stock" to listOf(mapOf("stock" to 2), mapOf("stock" to 1)))),
                            mapOf("model_id" to 32, "model_sku" to "ZZZ"),
                            mapOf("model_sku" to "ABC"),
                        ),
                    ),
                ),
            )
            .on("/product/update_stock", body = mapOf("error" to "", "response" to mapOf("failure_list" to emptyList<Any>())))
        val port = ShopeeStockPort(ShopeeApi(http, properties))

        val found = port.find(shop, "ABC")
        assertEquals(listOf("1" to null, "3" to "31"), found.map { it.itemId to it.variationId })
        assertEquals(5, found[0].quantity)
        assertNull(found[1].quantity, "two warehouses cannot be changed by a delta")
        val search = http.sent("search_item").single().url
        assertTrue(search.contains("shop_id=44120") && search.contains("item_sku=ABC") && search.contains("partner_id=10090"))

        port.setQuantity(shop, found[0], 4)
        port.setQuantity(shop, found[1].copy(quantity = 3), 2)
        val bodies = http.sent("update_stock").map { it.body }
        assertEquals(
            mapOf("item_id" to 1L, "stock_list" to listOf(mapOf("model_id" to 0L, "seller_stock" to listOf(mapOf("stock" to 4, "location_id" to "BR1"))))),
            bodies[0],
        )
        assertEquals(
            mapOf("item_id" to 3L, "stock_list" to listOf(mapOf("model_id" to 31L, "seller_stock" to listOf(mapOf("stock" to 2))))),
            bodies[1],
        )
    }

    @Test
    fun `shopee reports refusals and validates the push signature`() {
        val empty = FakeHttp().on("search_item", body = mapOf("response" to mapOf("item_id_list" to emptyList<Any>())))
        assertTrue(ShopeeStockPort(ShopeeApi(empty, properties)).find(shop, "ABC").isEmpty())

        val failing = FakeHttp()
            .on("update_stock", body = mapOf("response" to mapOf("failure_list" to listOf(mapOf("model_id" to 0, "failed_reason" to "reserved")))))
            .on("search_item", body = mapOf("error" to "error_auth", "message" to "Invalid access_token"))
        val port = ShopeeStockPort(ShopeeApi(failing, properties))
        assertTrue(assertThrows<UnprocessableException> { port.setQuantity(shop, StockListing("1", null, "x", 1), 0) }.message!!.contains("reserved"))
        assertTrue(assertThrows<UnprocessableException> { port.find(shop, "ABC") }.message!!.contains("Invalid access_token"))
        assertThrows<UnprocessableException> { port.find(ConnectionAccess("shopee", "tok", "main:1"), "ABC") }
        assertThrows<UnprocessableException> { ShopeeStockPort(ShopeeApi(failing, MarketplaceOAuthProperties())).find(shop, "ABC") }
        val down = FakeHttp().on("search_item", status = 500, body = null)
        assertTrue(assertThrows<UnprocessableException> { ShopeeStockPort(ShopeeApi(down, properties)).find(shop, "ABC") }.message!!.contains("500"))

        val api = ShopeeApi(http, properties)
        val body = """{"code":3}"""
        val signature = hmacHex("partner-key", "https://loja.example/api/v1/integrations/webhooks/shopee|$body")
        assertTrue(api.validPush(signature, body))
        assertTrue(api.validPush(signature.uppercase(), body))
        assertFalse(api.validPush(signature, """{"code":4}"""))
        assertFalse(api.validPush(null, body))
        assertFalse(ShopeeApi(http, MarketplaceOAuthProperties()).validPush(signature, body))
    }

    @Test
    fun `olx treats the ad whose id is the sku as one unit`() {
        http.on("published?fetch_size=200&page_token=p2", body = mapOf("data" to listOf(mapOf("id" to "abc", "subject" to "Bicicleta"))))
            .on("published?fetch_size=200", body = mapOf("data" to listOf(mapOf("id" to "OTHER")), "next_token" to "p2"))
            .on("autoupload/import", body = mapOf("statusCode" to 0))
            .on("autoservice/v1/notification", body = emptyMap<String, Any?>())
        val port = OlxStockPort(http, properties)

        val found = port.find(olx, "ABC")
        assertEquals(listOf(StockListing("abc", null, "Bicicleta", 1)), found)
        assertTrue(port.find(olx, "MISSING").isEmpty())

        port.setQuantity(olx, found.single(), 3)
        assertTrue(http.sent("PUT").isEmpty())
        assertThrows<UnprocessableException> { port.setQuantity(olx, found.single(), 0) }
        assertTrue(http.sent("PUT").isEmpty(), "ads are only deleted on opt-in")

        properties.olx.deleteSoldAds = true
        port.setQuantity(olx, found.single(), 0)
        assertEquals(
            mapOf("access_token" to "tok", "ad_list" to listOf(mapOf("id" to "abc", "operation" to "delete"))),
            http.sent("PUT").single().body,
        )

        assertFalse(port.registerWebhook("tok", "secret"))
        properties.olx.webhookUrl = "https://loja.example/api/v1/integrations/webhooks/olx"
        assertTrue(port.registerWebhook("tok", "secret"))
        assertEquals("secret", (http.sent("notification").single().body as Map<*, *>)["token"])
    }

    @Test
    fun `olx surfaces refusals and stops paging`() {
        val refused = FakeHttp().on("published", status = 401, body = null).on("import", body = mapOf("statusCode" to -6))
        properties.olx.deleteSoldAds = true
        val port = OlxStockPort(refused, properties)
        assertThrows<UnprocessableException> { port.find(olx, "ABC") }
        assertThrows<UnprocessableException> { port.setQuantity(olx, StockListing("abc", null, "x", 1), 0) }

        val endless = FakeHttp().on("published", body = mapOf("data" to emptyList<Any>(), "next_token" to "again"))
        assertTrue(OlxStockPort(endless, properties).find(olx, "ABC").isEmpty())
        assertEquals(10, endless.calls.size)
    }

    @Test
    fun `token leaser refreshes without a logged user and persists the rotation`() {
        val connections = Mockito.mock(ConnectionRepository::class.java)
        val oauth = Mockito.mock(MarketplaceOAuthService::class.java)
        val writer = Mockito.mock(StockConnectionTokenWriter::class.java)
        val expiry = Instant.parse("2026-11-10T12:00:00Z")
        val connection = ConnectionEntity(
            externalId = "ml",
            status = "ativa",
            accessToken = "old",
            refreshToken = "ref",
            tokenExpiresAt = expiry,
            sellerExternalId = "99",
        )
        Mockito.`when`(connections.findById(connection.id)).thenReturn(Optional.of(connection))
        Mockito.`when`(oauth.ensureAccessToken("ml", "old", "ref", expiry, "99")).thenReturn(TokenLease("new", "ref2", null, true))
        val leaser = ConnectionTokenLeaser(connections, oauth, writer)

        val access = leaser.lease(connection.id)
        assertEquals(listOf("ml", "new", "99"), listOf(access.provider, access.token, access.sellerId))
        assertEquals("ref2", connection.refreshToken)
        Mockito.verify(writer).persist(connection)

        Mockito.`when`(oauth.ensureAccessToken("ml", "new", "ref2", null, "99")).thenReturn(TokenLease("new", "ref2", null, false))
        leaser.lease(connection.id)
        Mockito.verify(writer, Mockito.times(1)).persist(connection)

        connection.status = "disponivel"
        assertThrows<UnprocessableException> { leaser.lease(connection.id) }
        val unknown = UUID.randomUUID()
        Mockito.`when`(connections.findById(unknown)).thenReturn(Optional.empty())
        assertThrows<UnprocessableException> { leaser.lease(unknown) }
    }
}
