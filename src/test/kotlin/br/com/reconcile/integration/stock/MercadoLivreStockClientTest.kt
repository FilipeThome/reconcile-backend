package br.com.reconcile.integration.stock

import br.com.reconcile.integration.oauth.HttpJson
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MercadoLivreStockClientTest {
    private val http = ScriptHttp()
    private val client = MercadoLivreStockClient(http, properties())

    @Test
    fun `seller id comes from users me and rejects a stored mismatch`() {
        http.enqueue(HttpJson(200, mapOf("id" to 99), emptyMap()))
        assertEquals("99", client.sellerId("tok", "99"))
        assertTrue(http.calls.single().url.endsWith("/users/me"))

        http.enqueue(HttpJson(200, mapOf("id" to 100), emptyMap()))
        val mismatch = assertThrows<UnprocessableException> { client.sellerId("tok", "99") }
        assertTrue(mismatch.message!!.contains("não corresponde"))

        http.enqueue(HttpJson(200, mapOf("id" to 195831534), emptyMap()))
        assertEquals("195831534", client.sellerId("tok", "MLB-204918"))

        http.enqueue(HttpJson(401, mapOf("message" to "invalid token"), emptyMap()))
        val denied = assertThrows<UnprocessableException> { client.sellerId("tok", "seller") }
        assertTrue(denied.message!!.contains("401"))

        http.enqueue(HttpJson(200, mapOf("nickname" to "loja"), emptyMap()))
        assertThrows<UnprocessableException> { client.sellerId("tok", "seller") }

        http.enqueue(HttpJson(200, mapOf("id" to "abc"), emptyMap()))
        assertThrows<UnprocessableException> { client.sellerId("tok", "seller") }

        val unconfigured = MercadoLivreStockClient(http, MarketplaceOAuthProperties())
        val missing = assertThrows<UnprocessableException> { unconfigured.sellerId("tok", "seller") }
        assertTrue(missing.message!!.contains("não configurada"))
    }

    @Test
    fun `list reads search pages variations and stock locations`() {
        http.enqueue(
            HttpJson(
                200,
                mapOf(
                    "results" to listOf("MLB10000001", "nope", null, "null", "MLB10000004"),
                    "paging" to mapOf("total" to 5),
                ),
                emptyMap(),
            ),
        )
        http.enqueue(
            HttpJson(
                200,
                listOf(
                    mapOf("code" to 200, "body" to item(id = "MLB10000001", userProduct = "MLBU10000001", quantity = 8)),
                    mapOf("code" to 404, "body" to mapOf("message" to "missing")),
                    mapOf("code" to 200, "body" to item(id = "MLB10000002", seller = "1")),
                    mapOf(
                        "code" to 200,
                        "body" to mapOf(
                            "id" to "MLB10000004",
                            "seller_id" to "99",
                            "status" to "paused",
                            "user_product_id" to "MLBU10000004",
                            "available_quantity" to 1,
                            "variations" to listOf(
                                mapOf(
                                    "id" to "15",
                                    "available_quantity" to 1,
                                    "seller_custom_field" to "VAR-SKU",
                                    "attribute_combinations" to listOf(
                                        mapOf("value_name" to "Azul"),
                                        mapOf("value_name" to "P"),
                                    ),
                                ),
                                mapOf("available_quantity" to 1),
                                mapOf("id" to true),
                            ),
                        ),
                    ),
                ),
                emptyMap(),
            ),
        )
        http.enqueue(HttpJson(200, mapOf("locations" to listOf(mapOf("type" to "selling_address", "quantity" to 8))), mapOf("X-Version" to "3")))
        http.enqueue(HttpJson(503, mapOf("message" to "down"), emptyMap()))

        val page = client.list("tok", "99", "CAM-1", "active", -3, 50)

        assertEquals(5, page.total)
        assertEquals(0, page.offset)
        assertEquals(20, page.limit)
        assertEquals(listOf("MLB10000001", "MLB10000004"), page.items.map { it.id })
        assertEquals("CAM-1", page.items[0].sku)
        assertEquals(8, page.items[0].locations[0].quantity)
        assertTrue(page.items[0].editable)
        assertEquals("MLB10000004", page.items[1].title)
        assertEquals(0, page.items[1].soldQuantity)
        assertNull(page.items[1].permalink)
        assertTrue(page.items[1].locations.isEmpty())
        assertFalse(page.items[1].editable)
        assertFalse(page.items[1].stockAvailable)
        assertEquals("Azul / P", page.items[1].variations.single().label)
        assertEquals("VAR-SKU", page.items[1].variations.single().sku)
        assertEquals(15L, page.items[1].variations.single().id)
        assertTrue(http.calls[0].url.contains("/users/99/items/search"))
        assertTrue(http.calls[0].url.contains("sku=CAM-1"))
        assertTrue(http.calls[0].url.contains("status=active"))
        assertTrue(http.calls[0].url.contains("limit=20"))
        assertTrue(http.calls[1].url.contains("ids=MLB10000001,MLB10000004"))
        assertTrue(http.calls[2].url.contains("/user-products/MLBU10000001/stock"))
    }

    @Test
    fun `list finds one item by id and hides other sellers or statuses`() {
        http.enqueue(HttpJson(200, item(status = "paused"), emptyMap()))
        val paused = client.list("tok", "99", "MLB10000001", "paused", 0, 20)
        assertEquals(1, paused.total)
        assertEquals("paused", paused.items.single().status)

        http.enqueue(HttpJson(200, item(status = "active"), emptyMap()))
        assertEquals(0, client.list("tok", "99", "MLB10000001", "paused", 0, 20).total)

        http.enqueue(HttpJson(404, mapOf("message" to "not found"), emptyMap()))
        assertEquals(0, client.list("tok", "99", "MLB10000001", null, 0, 20).total)

        http.enqueue(HttpJson(200, item(seller = "7"), emptyMap()))
        assertEquals(0, client.list("tok", "99", "MLB10000001", null, 0, 20).total)

        http.enqueue(HttpJson(200, mapOf("paging" to mapOf("total" to 0)), emptyMap()))
        val empty = client.list("tok", "99", null, null, 0, 1)
        assertEquals(0, empty.total)
        assertEquals(1, empty.limit)
        assertFalse(http.calls.last().url.contains("sku="))
        assertFalse(http.calls.last().url.contains("status="))

        http.enqueue(HttpJson(403, mapOf("cause" to listOf(mapOf("message" to "sem permissão")), "message" to "ignored"), emptyMap()))
        val denied = assertThrows<UnprocessableException> { client.list("tok", "99", "SKU", "active", 0, 20) }
        assertTrue(denied.message!!.contains("sem permissão"))

        http.enqueue(HttpJson(400, emptyMap<String, Any?>(), emptyMap()))
        assertTrue(assertThrows<UnprocessableException> { client.list("tok", "99", "SKU", null, 0, 20) }.message!!.contains("erro desconhecido"))

        assertThrows<UnprocessableException> { client.list("tok", "seller", null, null, 0, 20) }
    }

    @Test
    fun `get rejects unknown items and fulfillment stock stays read only`() {
        http.enqueue(HttpJson(404, mapOf("message" to "missing"), emptyMap()))
        assertThrows<NotFoundException> { client.get("tok", "99", "MLB10000001") }

        http.enqueue(HttpJson(200, item(seller = 99), emptyMap()))
        assertEquals(4, client.get("tok", "99", "MLB10000001").availableQuantity)

        http.enqueue(HttpJson(200, item(userProduct = "SHORT"), emptyMap()))
        val unknownProduct = client.get("tok", "99", "MLB10000001")
        assertTrue(unknownProduct.locations.isEmpty())
        assertFalse(unknownProduct.editable)
        assertFalse(unknownProduct.stockAvailable)

        http.enqueue(HttpJson(500, mapOf("message" to "boom"), emptyMap()))
        assertThrows<UnprocessableException> { client.get("tok", "99", "MLB10000001") }

        http.enqueue(
            HttpJson(
                200,
                item(userProduct = "MLBU10000001", logistic = "fulfillment", custom = "FULL"),
                emptyMap(),
            ),
        )
        http.enqueue(
            HttpJson(
                200,
                mapOf("locations" to listOf(mapOf("type" to "meli_facility", "quantity" to 3), mapOf("quantity" to 1))),
                mapOf("x-version" to ""),
            ),
        )
        val full = client.get("tok", "99", "MLB10000001")
        assertFalse(full.editable)
        assertEquals("FULL", full.sku)
        assertEquals("unknown", full.locations[1].type)
        assertFalse(full.locations[1].editable)

        assertThrows<UnprocessableException> { client.get("tok", "99", "../items") }

        val slashed = MercadoLivreStockClient(http, properties("https://api.mercadolibre.com/"))
        http.enqueue(HttpJson(200, item(), emptyMap()))
        slashed.get("tok", "99", "MLB10000001")
        assertTrue(http.calls.last().url.startsWith("https://api.mercadolibre.com/items/MLB10000001"))
    }

    @Test
    fun `update changes available quantity variations and flex stock`() {
        http.enqueue(HttpJson(200, item(quantity = 4), emptyMap()))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(quantity = 6), emptyMap()))
        val updated = client.update("tok", "99", "MLB10000001", 6, null, "  ")
        assertEquals(6, updated.availableQuantity)
        assertEquals(mapOf("available_quantity" to 6), http.calls[1].body)

        val variation = mapOf(
            "id" to 11,
            "available_quantity" to 2,
            "attribute_combinations" to emptyList<Any>(),
            "user_product_id" to "",
        )
        http.enqueue(HttpJson(200, item(id = "MLA10000001", variations = listOf(variation), userProduct = "MLAU10000001"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 2))))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(id = "MLA10000001", variations = listOf(variation), userProduct = "MLAU10000001", quantity = 2), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 2))))
        val varied = client.update("tok", "99", "MLA10000001", 5, 11L, null)
        assertEquals(11L, varied.variations.single().id)
        assertEquals("11", varied.variations.single().label)
        val variationBody = http.calls.last { it.method == "PUT" }.body as Map<*, *>
        val sentVariation = (variationBody["variations"] as List<*>).single() as Map<*, *>
        assertEquals(11L, (sentVariation["id"] as Number).toLong())
        assertEquals(5, (sentVariation["available_quantity"] as Number).toInt())

        http.enqueue(HttpJson(200, item(id = "MLC10000009", userProduct = "MLCU10000009"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 1))))
        http.enqueue(HttpJson(204, null, emptyMap()))
        http.enqueue(HttpJson(200, item(id = "MLC10000009", userProduct = "MLCU10000009", quantity = 9), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 9))))
        val flex = client.update("tok", "99", "MLC10000009", 9, null, null)
        assertEquals(9, flex.availableQuantity)
        assertEquals("4", http.calls.first { it.url.contains("/stock/type/selling_address") }.headers["x-version"])
    }

    @Test
    fun `update uses seller warehouse and retries a stale version`() {
        val locations = listOf(
            mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 4),
            mapOf("type" to "seller_warehouse", "store_id" to "B2", "network_node_id" to "N2", "quantity" to 5),
        )
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(locations))
        http.enqueue(HttpJson(409, mapOf("message" to "version"), emptyMap()))
        val refreshed = listOf(
            mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 4),
            mapOf("type" to "seller_warehouse", "store_id" to "B2", "network_node_id" to "N2", "quantity" to 50),
        )
        http.enqueue(HttpJson(200, mapOf("locations" to refreshed), mapOf("x-version" to "8")))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", quantity = 7), emptyMap()))
        http.enqueue(stock(locations))
        val updated = client.update("tok", "99", "MLB10000001", 7, null, "A1")
        assertEquals(7, updated.availableQuantity)
        val put = http.calls.filter { it.method == "PUT" }
        assertEquals(listOf("4", "8"), put.map { it.headers["x-version"] })
        val firstBody = put.first().body as Map<*, *>
        val firstSent = firstBody["locations"] as List<*>
        assertEquals(5, (firstSent[1] as Map<*, *>)["quantity"])
        val body = put.last().body as Map<*, *>
        val sent = body["locations"] as List<*>
        assertEquals(2, sent.size)
        assertEquals(7, (sent[0] as Map<*, *>)["quantity"])
        assertEquals(50, (sent[1] as Map<*, *>)["quantity"])

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", logistic = "fulfillment"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 1))))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", logistic = "fulfillment"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 2))))
        client.update("tok", "99", "MLB10000001", 2, null, null)
        assertTrue(http.calls.last { it.method == "PUT" }.url.contains("seller_warehouse"))
    }

    @Test
    fun `update writes flex stock on every site and rejects full stock`() {
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 3), mapOf("type" to "meli_facility", "quantity" to 1))))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", quantity = 6), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 6))))
        assertEquals(6, client.update("tok", "99", "MLB10000001", 6, null, null).availableQuantity)
        assertTrue(http.calls.last { it.method == "PUT" }.url.contains("/stock/type/selling_address"))

        val variation = mapOf("id" to 11, "available_quantity" to 1, "user_product_id" to "MLBU20000002")
        http.enqueue(HttpJson(200, item(variations = listOf(variation)), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 1)), version = "9"))
        http.enqueue(HttpJson(409, mapOf("message" to "version"), emptyMap()))
        http.enqueue(HttpJson(200, mapOf("locations" to listOf(mapOf("type" to "selling_address", "quantity" to 1))), emptyMap()))
        val missingVersion = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 4, 11L, null) }
        assertTrue(missingVersion.message!!.contains("versão"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "meli_facility", "quantity" to 3))))
        val full = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }
        assertTrue(full.message!!.contains("Full"))

        http.enqueue(HttpJson(200, item(logistic = "fulfillment"), emptyMap()))
        val logistic = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }
        assertTrue(logistic.message!!.contains("Full"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(
            HttpJson(
                200,
                mapOf("locations" to listOf(mapOf("type" to "meli_facility", "quantity" to 1), mapOf("type" to "unknown", "quantity" to 1))),
                mapOf("x-version" to "1"),
            ),
        )
        val mixed = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }
        assertTrue(mixed.message!!.contains("não aceita atualização"))

        http.enqueue(HttpJson(200, item(status = "closed"), emptyMap()))
        val closed = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }
        assertTrue(closed.message!!.contains("closed"))

        http.enqueue(HttpJson(200, item(status = "under_review", userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "selling_address", "quantity" to 1))))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }.message!!.contains("under_review"))
    }

    @Test
    fun `update reports marketplace and validation errors`() {
        http.enqueue(HttpJson(200, item(), emptyMap()))
        http.enqueue(HttpJson(400, mapOf("message" to "quantity too high"), emptyMap()))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 3, null, null) }.message!!.contains("quantity too high"))

        http.enqueue(HttpJson(200, item(), emptyMap()))
        http.enqueue(HttpJson(404, mapOf("message" to "gone"), emptyMap()))
        assertThrows<NotFoundException> { client.update("tok", "99", "MLB10000001", 3, null, null) }

        val locations = listOf(
            mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 1),
            mapOf("type" to "seller_warehouse", "store_id" to "B2", "network_node_id" to "N2", "quantity" to 1),
        )
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(locations))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }.message!!.contains("mais de um depósito"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(locations))
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, "C3") }

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "seller_warehouse", "store_id" to "A1", "quantity" to 1))))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, "A1") }.message!!.contains("identificador"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(HttpJson(200, mapOf("locations" to listOf(mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 1))), emptyMap()))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }.message!!.contains("versão"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 1))))
        http.enqueue(HttpJson(409, mapOf("message" to "version"), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 3)), version = "5"))
        http.enqueue(HttpJson(409, mapOf("message" to "still stale"), emptyMap()))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }.message!!.contains("still stale"))

        http.enqueue(HttpJson(200, item(variations = listOf(mapOf("id" to 11, "available_quantity" to 1, "user_product_id" to "MLBU20000002"))), emptyMap()))
        http.enqueue(stock(listOf(mapOf("type" to "meli_facility", "quantity" to 2))))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, 11L, null) }.message!!.contains("Full"))

        http.enqueue(HttpJson(200, item(variations = listOf(mapOf("id" to 11, "available_quantity" to 1))), emptyMap()))
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, null) }
        http.enqueue(HttpJson(200, item(variations = listOf(mapOf("id" to 11, "available_quantity" to 1))), emptyMap()))
        assertThrows<NotFoundException> { client.update("tok", "99", "MLB10000001", 1, 99L, null) }

        http.enqueue(HttpJson(200, item(variations = listOf(mapOf("id" to 11, "available_quantity" to 1, "user_product_id" to "bad"))), emptyMap()))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, 11L, null) }.message!!.contains("user_product_id"))

        http.enqueue(HttpJson(200, item(variations = listOf(mapOf("id" to 11, "available_quantity" to 1, "user_product_id" to "MLBU20000002"))), emptyMap()))
        http.enqueue(HttpJson(404, mapOf("message" to "stock missing"), emptyMap()))
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, 11L, null) }

        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", -1, null, null) }.message!!.contains("99999"))
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 100_000, null, null) }
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, 0L, null) }
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, "bad store") }
        assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 1, null, "A1", "bad node") }

        val classic = mapOf("id" to 11, "available_quantity" to 2)
        val parentWarehouses = listOf(
            mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 4),
            mapOf("type" to "seller_warehouse", "store_id" to "B2", "network_node_id" to "N2", "quantity" to 5),
        )
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", variations = listOf(classic)), emptyMap()))
        http.enqueue(stock(parentWarehouses))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", variations = listOf(classic)), emptyMap()))
        http.enqueue(stock(parentWarehouses))
        client.update("tok", "99", "MLB10000001", 5, 11L, null)
        val variationPut = http.calls.last { it.method == "PUT" }
        assertTrue(variationPut.url.contains("/items/MLB10000001"))
        assertFalse(variationPut.url.contains("seller_warehouse"))

        val sharedStore = listOf(
            mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 4),
            mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N2", "quantity" to 5),
        )
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(sharedStore))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 7, null, "A1") }.message!!.contains("nó"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(stock(sharedStore))
        http.enqueue(HttpJson(200, emptyMap<String, Any?>(), emptyMap()))
        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001", quantity = 7), emptyMap()))
        http.enqueue(stock(sharedStore))
        client.update("tok", "99", "MLB10000001", 7, null, "A1", "N2")
        val nodes = ((http.calls.last { it.method == "PUT" }.body as Map<*, *>)["locations"] as List<*>).map { it as Map<*, *> }
        assertEquals(4, nodes.first { it["network_node_id"] == "N1" }["quantity"])
        assertEquals(7, nodes.first { it["network_node_id"] == "N2" }["quantity"])

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(
            stock(
                listOf(
                    mapOf("type" to "seller_warehouse", "store_id" to "A1", "network_node_id" to "N1", "quantity" to 4),
                    mapOf("type" to "seller_warehouse", "store_id" to "B2", "network_node_id" to "N2"),
                ),
            ),
        )
        val missingQuantity = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 2, null, "A1") }
        assertTrue(missingQuantity.message!!.contains("quantidade de um depósito"))
        assertFalse(http.calls.last().method == "PUT")

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(HttpJson(503, mapOf("message" to "down"), emptyMap()))
        val unread = assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 2, null, null) }
        assertTrue(unread.message!!.contains("ler o estoque"))

        http.enqueue(HttpJson(200, item(userProduct = "MLBU10000001"), emptyMap()))
        http.enqueue(HttpJson(200, mapOf("locations" to listOf(mapOf("type" to "selling_address", "quantity" to 1))), mapOf("x-version" to "4 evil")))
        assertTrue(assertThrows<UnprocessableException> { client.update("tok", "99", "MLB10000001", 2, null, null) }.message!!.contains("versão"))

        http.enqueue(HttpJson(200, mapOf("results" to "MLB10000001", "paging" to mapOf("total" to 1)), emptyMap()))
        assertTrue(client.list("tok", "99", null, null, 0, 20).items.isEmpty())
        http.enqueue(HttpJson(200, mapOf("results" to listOf("MLB10000001"), "paging" to mapOf("total" to 1)), emptyMap()))
        http.enqueue(HttpJson(200, mapOf("error" to "not-a-list"), emptyMap()))
        assertTrue(client.list("tok", "99", null, null, 0, 20).items.isEmpty())
        http.enqueue(HttpJson(200, mapOf("results" to listOf("MLB10000001"), "paging" to mapOf("total" to 1)), emptyMap()))
        http.enqueue(HttpJson(400, mapOf("message" to "multiget failed"), emptyMap()))
        assertThrows<UnprocessableException> { client.list("tok", "99", null, null, 0, 20) }
    }

    @Test
    fun `list matches a lowercase item id and a title when the sku is missing`() {
        http.enqueue(HttpJson(200, item(), emptyMap()))
        val byId = client.list("tok", "99", "mlb10000001", null, 0, 20)
        assertEquals("MLB10000001", byId.items.single().id)
        assertTrue(http.calls.last().url.endsWith("/items/MLB10000001"))

        http.enqueue(HttpJson(200, mapOf("results" to emptyList<String>(), "paging" to mapOf("total" to 0)), emptyMap()))
        http.enqueue(HttpJson(200, mapOf("results" to listOf("MLB10000001"), "paging" to mapOf("total" to 1)), emptyMap()))
        http.enqueue(HttpJson(200, listOf(mapOf("code" to 200, "body" to item())), emptyMap()))
        val byTitle = client.list("tok", "99", "Camiseta", null, 0, 20)
        assertEquals("MLB10000001", byTitle.items.single().id)
        assertTrue(http.calls.any { it.url.contains("sku=Camiseta") })
        assertTrue(http.calls.any { it.url.contains("q=Camiseta") })
    }

    private fun properties(url: String = "https://api.mercadolibre.com") = MarketplaceOAuthProperties().apply {
        mercadoLivre.apiUrl = url
    }

    private fun stock(locations: List<Map<String, Any?>>, version: String = "4") =
        HttpJson(200, mapOf("locations" to locations), mapOf("x-version" to version))

    private fun item(
        id: String = "MLB10000001",
        seller: Any = "99",
        status: String = "active",
        quantity: Int = 4,
        userProduct: String? = null,
        logistic: String? = null,
        variations: List<Map<String, Any?>> = emptyList(),
        custom: String? = null,
    ): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "id" to id,
            "title" to "Camiseta",
            "seller_id" to seller,
            "status" to status,
            "available_quantity" to quantity,
            "sold_quantity" to 2,
            "permalink" to "https://produto.mercadolivre.com.br/$id",
            "variations" to variations,
            "attributes" to if (custom == null) listOf(mapOf("id" to "SELLER_SKU", "value_name" to "CAM-1")) else emptyList<Any>(),
        )
        if (userProduct != null) payload["user_product_id"] = userProduct
        if (logistic != null) payload["shipping"] = mapOf("logistic_type" to logistic)
        if (custom != null) payload["seller_custom_field"] = custom
        return payload
    }

    private class ScriptHttp : JsonHttpClient {
        val calls = mutableListOf<Call>()
        private val steps = ArrayDeque<(Call) -> HttpJson>()

        fun enqueue(response: HttpJson) {
            steps.add { response }
        }

        override fun requestJson(method: String, url: String, bearer: String, body: Any?, headers: Map<String, String>): HttpJson {
            val call = Call(method, url, body, headers)
            calls += call
            return steps.removeFirstOrNull()?.invoke(call) ?: error("unexpected $method $url")
        }

        override fun postForm(url: String, form: Map<String, String>) = unsupported()
        override fun postJson(url: String, body: Map<String, Any?>) = unsupported()
        override fun getJson(url: String, bearer: String) = unsupported()
        override fun getJsonOrEmpty(url: String, bearer: String) = unsupported()
        override fun getAmazonJson(url: String, headers: Map<String, String>) = unsupported()
        override fun getAmazonJsonOrEmpty(url: String, headers: Map<String, String>) = unsupported()

        private fun unsupported(): Map<String, Any?> = error("not used")

        data class Call(val method: String, val url: String, val body: Any?, val headers: Map<String, String>)
    }
}
