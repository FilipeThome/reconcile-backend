package br.com.reconcile.integration.stock

import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.integration.oauth.HttpJson
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder

class StockPage(
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<StockItem>,
)

class StockItem(
    val id: String,
    val title: String,
    val sku: String?,
    val status: String,
    val availableQuantity: Int,
    val soldQuantity: Int,
    val permalink: String?,
    val userProductId: String?,
    val editable: Boolean,
    val variations: List<StockVariation>,
    val locations: List<StockLocation>,
    val stockAvailable: Boolean = true,
)

class StockVariation(
    val id: Long,
    val label: String,
    val sku: String?,
    val availableQuantity: Int,
    val userProductId: String?,
)

class StockLocation(
    val type: String,
    val quantity: Int,
    val storeId: String?,
    val networkNodeId: String?,
    val editable: Boolean,
    @get:JsonIgnore val quantityKnown: Boolean = true,
)

@Component
class MercadoLivreStockClient(
    private val http: JsonHttpClient,
    private val properties: MarketplaceOAuthProperties,
) {
    fun sellerId(token: String, stored: String): String {
        val response = remoteCall("GET", "${base()}/users/me", token)
        if (response.status >= 400) throw remote(response)
        val id = CatalogJson.text(CatalogJson.map(response.body), "id")
        if (id == null || !SELLER_ID.matches(id)) {
            throw UnprocessableException("Não foi possível identificar o seller do Mercado Livre.")
        }
        if (SELLER_ID.matches(stored) && stored != id) {
            throw UnprocessableException("O seller salvo não corresponde à conta conectada. Reconecte o Mercado Livre.")
        }
        return id
    }

    fun list(token: String, sellerId: String, query: String?, status: String?, offset: Int, limit: Int): StockPage {
        requireSeller(sellerId)
        val safeLimit = limit.coerceIn(1, 20)
        val safeOffset = offset.coerceAtLeast(0)
        val normalized = query?.trim()?.takeIf { it.isNotBlank() }
        if (normalized != null && ITEM_ID.matches(normalized.uppercase())) {
            return listedItem(token, sellerId, normalized.uppercase(), status, safeLimit)
        }
        val bySku = search(token, sellerId, normalized, null, status, safeOffset, safeLimit)
        if (normalized == null || bySku.items.isNotEmpty()) return bySku
        val byTitle = search(token, sellerId, null, normalized, status, safeOffset, safeLimit)
        return if (byTitle.items.isNotEmpty()) byTitle else bySku
    }

    fun get(token: String, sellerId: String, itemId: String): StockItem = load(token, sellerId, itemId).view

    fun update(
        token: String,
        sellerId: String,
        itemId: String,
        quantity: Int,
        variationId: Long?,
        storeId: String?,
        networkNodeId: String? = null,
    ): StockItem {
        if (quantity !in 0..99_999) throw UnprocessableException("A quantidade deve ficar entre 0 e 99999.")
        val cleanStore = storeId?.trim()?.ifBlank { null }
        val cleanNode = networkNodeId?.trim()?.ifBlank { null }
        if (cleanStore != null && !STORE_ID.matches(cleanStore)) throw UnprocessableException("Depósito inválido.")
        if (cleanNode != null && !NODE_ID.matches(cleanNode)) throw UnprocessableException("Depósito inválido.")
        if (variationId != null && variationId <= 0) throw UnprocessableException("Variação inválida.")
        val loaded = load(token, sellerId, itemId)
        val variation = variationOf(loaded.view, variationId)
        if (variation != null && variation.userProductId.isNullOrBlank()) {
            if (loaded.view.status in LOCKED_STATUS || loaded.fulfillment || (!loaded.stockUnavailable && !loaded.view.editable)) {
                throw UnprocessableException(readOnlyMessage(loaded.view))
            }
            ensureOk(putItem(itemId, token, mapOf("variations" to listOf(mapOf("id" to variation.id, "available_quantity" to quantity)))))
            return load(token, sellerId, itemId).view
        }
        if (loaded.stockUnavailable) {
            throw UnprocessableException("Não foi possível ler o estoque deste anúncio no Mercado Livre.")
        }
        if (!loaded.view.editable) throw UnprocessableException(readOnlyMessage(loaded.view))
        val variationProduct = variation?.userProductId?.takeIf { it.isNotBlank() }
        val snapshot = if (variationProduct != null) {
            readStock(token, variationProduct, required = true)
        } else {
            StockSnapshot(loaded.locations, loaded.version)
        }
        val productId = variationProduct ?: loaded.view.userProductId
        val warehouses = snapshot.locations.filter { it.type == "seller_warehouse" }
        if (warehouses.isNotEmpty()) {
            putWarehouses(token, requireUserProduct(productId), warehouses, cleanStore, cleanNode, quantity, snapshot.version)
            return load(token, sellerId, itemId).view
        }
        if (snapshot.locations.any { it.type == "selling_address" }) {
            putSellingAddress(token, requireUserProduct(productId), quantity, snapshot.version)
            return load(token, sellerId, itemId).view
        }
        if (snapshot.locations.isNotEmpty() && snapshot.locations.none { it.editable }) {
            throw UnprocessableException("Estoque Full (Mercado Livre) não pode ser alterado por esta API.")
        }
        ensureOk(putItem(itemId, token, mapOf("available_quantity" to quantity)))
        return load(token, sellerId, itemId).view
    }

    private fun listedItem(token: String, sellerId: String, itemId: String, status: String?, limit: Int): StockPage {
        val view = try {
            load(token, sellerId, itemId).view
        } catch (_: NotFoundException) {
            return StockPage(0, 0, limit, emptyList())
        }
        if (status != null && view.status != status) return StockPage(0, 0, limit, emptyList())
        return StockPage(1, 0, limit, listOf(view))
    }

    private fun variationOf(item: StockItem, variationId: Long?): StockVariation? {
        if (item.variations.isNotEmpty() && variationId == null) {
            throw UnprocessableException("Informe a variação para atualizar o estoque.")
        }
        if (variationId == null) return null
        return item.variations.find { it.id == variationId }
            ?: throw NotFoundException("Variação não encontrada neste anúncio.")
    }

    private fun search(
        token: String,
        sellerId: String,
        sku: String?,
        text: String?,
        status: String?,
        offset: Int,
        limit: Int,
    ): StockPage {
        val response = remoteCall("GET", searchUrl(sellerId, sku, text, status, offset, limit), token)
        if (response.status >= 400) throw remote(response)
        val payload = CatalogJson.map(response.body)
        val ids = stringList(payload["results"]).filter { ITEM_ID.matches(it) }
        val total = CatalogJson.intValue(CatalogJson.child(payload, "paging"), "total", ids.size)
        val items = if (ids.isEmpty()) emptyList() else multiget(token, sellerId, ids)
        return StockPage(total, offset, limit, items)
    }

    private fun load(token: String, sellerId: String, itemId: String): Loaded {
        requireSeller(sellerId)
        requireItemId(itemId)
        val response = remoteCall("GET", "${base()}/items/$itemId", token)
        if (response.status == 404) throw NotFoundException("Anúncio $itemId não encontrado.")
        if (response.status >= 400) throw remote(response)
        val item = CatalogJson.map(response.body)
        if (CatalogJson.text(item, "seller_id") != sellerId) {
            throw NotFoundException("Anúncio não encontrado na conta conectada.")
        }
        val userProductId = CatalogJson.text(item, "user_product_id")
        val stock = if (userProductId.isNullOrBlank()) {
            StockSnapshot(emptyList(), null)
        } else {
            readStock(token, userProductId, required = false)
        }
        val logistic = CatalogJson.text(CatalogJson.child(item, "shipping"), "logistic_type")
        return Loaded(
            stock.locations,
            stock.version,
            toView(item, stock.locations, !stock.unavailable),
            stock.unavailable,
            logistic == "fulfillment",
        )
    }

    private fun multiget(token: String, sellerId: String, ids: List<String>): List<StockItem> {
        val attributes = "id,title,available_quantity,sold_quantity,status,permalink,seller_id,seller_custom_field,user_product_id,inventory_id,variations,attributes,shipping"
        val url = "${base()}/items?ids=${ids.joinToString(",")}&attributes=$attributes"
        val response = remoteCall("GET", url, token)
        if (response.status >= 400) throw remote(response)
        return entries(response.body).mapNotNull { entry ->
            if (CatalogJson.intValue(entry, "code", 200) != 200) return@mapNotNull null
            val item = CatalogJson.map(entry["body"])
            if (item.isEmpty() || CatalogJson.text(item, "seller_id") != sellerId) return@mapNotNull null
            val userProductId = CatalogJson.text(item, "user_product_id")
            val stock = if (userProductId.isNullOrBlank()) null else readStock(token, userProductId, required = false)
            toView(item, stock?.locations ?: emptyList(), stock?.unavailable != true)
        }
    }

    private fun readStock(token: String, userProductId: String, required: Boolean): StockSnapshot {
        if (!USER_PRODUCT_ID.matches(userProductId)) {
            if (required) throw UnprocessableException("user_product_id inválido.")
            return StockSnapshot(emptyList(), null, unavailable = true)
        }
        val response = remoteCall("GET", "${base()}/user-products/$userProductId/stock", token)
        if (response.status >= 400) {
            if (required) throw remote(response)
            return StockSnapshot(emptyList(), null, unavailable = true)
        }
        return StockSnapshot(locationsOf(response.body), header(response.headers, "x-version"))
    }

    private fun putWarehouses(
        token: String,
        userProductId: String,
        warehouses: List<StockLocation>,
        storeId: String?,
        networkNodeId: String?,
        quantity: Int,
        version: String?,
    ) {
        val target = selectWarehouse(warehouses, storeId, networkNodeId)
        val url = "${base()}/user-products/$userProductId/stock/type/seller_warehouse"
        val response = putVersioned(token, url, warehouseBody(warehouses, target, quantity), version) {
            val fresh = readStock(token, userProductId, required = true)
            val freshWarehouses = fresh.locations.filter { it.type == "seller_warehouse" }
            val freshTarget = selectWarehouse(freshWarehouses, target.storeId, target.networkNodeId)
            fresh.version to warehouseBody(freshWarehouses, freshTarget, quantity)
        }
        ensureOk(response)
    }

    private fun selectWarehouse(warehouses: List<StockLocation>, storeId: String?, networkNodeId: String?): StockLocation {
        val matches = when {
            storeId != null -> warehouses.filter { location ->
                location.storeId == storeId && (networkNodeId == null || location.networkNodeId == networkNodeId)
            }
            warehouses.size == 1 -> warehouses.filter { networkNodeId == null || it.networkNodeId == networkNodeId }
            else -> throw UnprocessableException("Há mais de um depósito. Informe qual estoque atualizar.")
        }
        if (matches.isEmpty()) throw UnprocessableException("Depósito não encontrado neste anúncio.")
        if (matches.size > 1) {
            throw UnprocessableException("Há mais de um depósito com esse identificador. Informe o nó do depósito.")
        }
        val target = matches.single()
        if (target.storeId.isNullOrBlank() || target.networkNodeId.isNullOrBlank()) {
            throw UnprocessableException("Depósito sem identificador para atualizar o estoque.")
        }
        return target
    }

    private fun warehouseBody(warehouses: List<StockLocation>, target: StockLocation, quantity: Int): Map<String, Any?> {
        if (warehouses.any { !sameWarehouse(it, target) && !it.quantityKnown }) {
            throw UnprocessableException("A API do Mercado Livre não informou a quantidade de um depósito.")
        }
        return mapOf(
            "locations" to warehouses.map { location ->
                mapOf(
                    "store_id" to location.storeId,
                    "network_node_id" to location.networkNodeId,
                    "quantity" to if (sameWarehouse(location, target)) quantity else location.quantity,
                )
            },
        )
    }

    private fun sameWarehouse(left: StockLocation, right: StockLocation) =
        left.storeId == right.storeId && left.networkNodeId == right.networkNodeId

    private fun putSellingAddress(token: String, userProductId: String, quantity: Int, version: String?) {
        val body = mapOf("quantity" to quantity)
        val response = putVersioned(
            token,
            "${base()}/user-products/$userProductId/stock/type/selling_address",
            body,
            version,
        ) {
            readStock(token, userProductId, required = true).version to body
        }
        ensureOk(response)
    }

    private fun putVersioned(
        token: String,
        url: String,
        body: Map<String, Any?>,
        version: String?,
        refresh: () -> Pair<String?, Map<String, Any?>>,
    ): HttpJson {
        val current = versionHeader(version)
        val first = putJson(url, token, body, mapOf("x-version" to current))
        if (first.status != 409) return first
        val (nextVersion, nextBody) = refresh()
        return putJson(url, token, nextBody, mapOf("x-version" to versionHeader(nextVersion)))
    }

    private fun versionHeader(version: String?): String {
        if (version == null || !VERSION.matches(version)) {
            throw UnprocessableException("A API do Mercado Livre não informou a versão do estoque.")
        }
        return version
    }

    private fun putItem(itemId: String, token: String, body: Map<String, Any?>) =
        putJson("${base()}/items/$itemId", token, body, emptyMap())

    private fun putJson(url: String, token: String, body: Any, headers: Map<String, String>) =
        remoteCall("PUT", url, token, body, headers)

    private fun remoteCall(
        method: String,
        url: String,
        token: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpJson {
        val root = base()
        if (!url.startsWith("$root/")) throw UnprocessableException("URL do Mercado Livre inválida.")
        return http.requestJson(method, url, token, body, headers)
    }

    private fun searchUrl(sellerId: String, sku: String?, text: String?, status: String?, offset: Int, limit: Int): String {
        val builder = UriComponentsBuilder.fromUriString("${base()}/users/$sellerId/items/search")
            .queryParam("limit", limit)
            .queryParam("offset", offset)
        if (!sku.isNullOrBlank()) builder.queryParam("sku", sku)
        if (!text.isNullOrBlank()) builder.queryParam("q", text)
        if (!status.isNullOrBlank()) builder.queryParam("status", status)
        return builder.build().encode().toUriString()
    }

    private fun toView(item: Map<String, Any?>, locations: List<StockLocation>, stockAvailable: Boolean = true): StockItem {
        val id = CatalogJson.text(item, "id") ?: ""
        val status = CatalogJson.text(item, "status") ?: ""
        val logistic = CatalogJson.text(CatalogJson.child(item, "shipping"), "logistic_type")
        val variations = CatalogJson.maps(item["variations"]).mapNotNull { row ->
            val variationId = longValue(row, "id") ?: return@mapNotNull null
            StockVariation(
                id = variationId,
                label = variationLabel(row),
                sku = skuOf(row),
                availableQuantity = CatalogJson.intValue(row, "available_quantity"),
                userProductId = CatalogJson.text(row, "user_product_id"),
            )
        }
        return StockItem(
            id = id,
            title = CatalogJson.text(item, "title") ?: id,
            sku = skuOf(item),
            status = status,
            availableQuantity = CatalogJson.intValue(item, "available_quantity"),
            soldQuantity = CatalogJson.intValue(item, "sold_quantity"),
            permalink = CatalogJson.text(item, "permalink"),
            userProductId = CatalogJson.text(item, "user_product_id"),
            editable = isEditable(status, locations, logistic, stockAvailable),
            variations = variations,
            locations = locations,
            stockAvailable = stockAvailable,
        )
    }

    private fun isEditable(status: String, locations: List<StockLocation>, logistic: String?, stockAvailable: Boolean): Boolean {
        if (!stockAvailable) return false
        if (status in LOCKED_STATUS) return false
        if (locations.any { it.editable }) return true
        if (locations.any { it.type == "meli_facility" }) return false
        if (logistic == "fulfillment") return false
        return true
    }

    private fun readOnlyMessage(item: StockItem): String {
        if (item.status in LOCKED_STATUS) return "Anúncios com status ${item.status} não aceitam atualização de estoque."
        if (item.locations.isEmpty() || item.locations.all { it.type == "meli_facility" }) {
            return "Estoque Full (Mercado Livre) não pode ser alterado por esta API."
        }
        return "Este anúncio não aceita atualização de estoque."
    }

    private fun locationsOf(body: Any?): List<StockLocation> =
        CatalogJson.maps(CatalogJson.map(body)["locations"]).map { row ->
            val type = CatalogJson.text(row, "type") ?: "unknown"
            val known = when (val raw = row["quantity"]) {
                is Number -> true
                is String -> raw.toIntOrNull() != null
                else -> false
            }
            StockLocation(
                type = type,
                quantity = if (known) CatalogJson.intValue(row, "quantity") else 0,
                storeId = CatalogJson.text(row, "store_id"),
                networkNodeId = CatalogJson.text(row, "network_node_id"),
                editable = type == "selling_address" || type == "seller_warehouse",
                quantityKnown = known,
            )
        }

    private fun skuOf(item: Map<String, Any?>): String? {
        val fromAttribute = CatalogJson.maps(item["attributes"])
            .firstOrNull { CatalogJson.text(it, "id") == "SELLER_SKU" }
            ?.let { CatalogJson.text(it, "value_name") }
        return fromAttribute ?: CatalogJson.text(item, "seller_custom_field")
    }

    private fun variationLabel(row: Map<String, Any?>): String {
        val parts = CatalogJson.maps(row["attribute_combinations"]).mapNotNull { CatalogJson.text(it, "value_name") }
        return parts.joinToString(" / ").ifBlank { CatalogJson.text(row, "id") ?: "variação" }
    }

    private fun requireUserProduct(userProductId: String?): String {
        if (userProductId.isNullOrBlank()) throw UnprocessableException("Anúncio sem user product para atualizar este estoque.")
        return userProductId
    }

    private fun ensureOk(response: HttpJson) {
        if (response.status == 404) throw NotFoundException("Anúncio não encontrado.")
        if (response.status >= 400) throw remote(response)
    }

    private fun remote(response: HttpJson): UnprocessableException {
        val map = CatalogJson.map(response.body)
        val cause = CatalogJson.maps(map["cause"]).firstNotNullOfOrNull { CatalogJson.text(it, "message") }
        val message = cause ?: CatalogJson.text(map, "message") ?: "erro desconhecido"
        return UnprocessableException("A API do Mercado Livre recusou a operação (${response.status}): $message")
    }

    private fun requireSeller(sellerId: String) {
        if (!SELLER_ID.matches(sellerId)) throw UnprocessableException("Seller do Mercado Livre inválido. Reconecte a integração.")
    }

    private fun requireItemId(itemId: String) {
        if (!ITEM_ID.matches(itemId)) throw UnprocessableException("Identificador de anúncio inválido.")
    }

    private fun base(): String {
        val configured = properties.mercadoLivre.apiUrl.trim()
        if (configured.isBlank()) throw UnprocessableException("API do Mercado Livre não configurada.")
        return configured.trimEnd('/')
    }

    private fun header(headers: Map<String, String>, name: String) =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun stringList(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { it?.toString()?.takeIf { value -> value.isNotBlank() && value != "null" } }
        else -> emptyList()
    }

    private fun entries(raw: Any?): List<Map<String, Any?>> = when (raw) {
        is List<*> -> raw.map { CatalogJson.map(it) }
        else -> emptyList()
    }

    private fun longValue(payload: Map<String, Any?>, key: String): Long? = when (val raw = payload[key]) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }

    private class Loaded(
        val locations: List<StockLocation>,
        val version: String?,
        val view: StockItem,
        val stockUnavailable: Boolean,
        val fulfillment: Boolean,
    )

    private class StockSnapshot(
        val locations: List<StockLocation>,
        val version: String?,
        val unavailable: Boolean = false,
    )

    private companion object {
        val ITEM_ID = Regex("^[A-Z]{3}\\d{5,20}$")
        val USER_PRODUCT_ID = Regex("^[A-Z]{3,4}\\d{5,20}$")
        val SELLER_ID = Regex("^\\d{1,20}$")
        val STORE_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
        val NODE_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
        val VERSION = Regex("^[A-Za-z0-9._:-]{1,64}$")
        val LOCKED_STATUS = setOf("closed", "inactive", "under_review")
    }
}
