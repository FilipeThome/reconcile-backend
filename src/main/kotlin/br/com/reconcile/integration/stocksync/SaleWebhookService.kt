package br.com.reconcile.integration.stocksync

import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.infra.RawEventEntity
import br.com.reconcile.integration.infra.RawEventRepository
import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Sale notifications pushed by the marketplaces. A notification is authenticated, stored in
 * raw_events and answered at once (Mercado Livre allows 500 ms); the marketplace API calls run on
 * the executor, with a few spaced attempts because the marketplace will not send it again after
 * the 200. Rows still pending after a restart are replayed.
 */
@Service
class SaleWebhookService(
    private val connections: ConnectionRepository,
    private val events: RawEventRepository,
    private val oauth: MarketplaceOAuthService,
    private val properties: MarketplaceOAuthProperties,
    private val leaser: ConnectionTokenLeaser,
    private val shopee: ShopeeApi,
    private val http: JsonHttpClient,
    private val sync: SaleStockSyncService,
    private val executor: TaskExecutor,
    @Value("\${reconcile.stock-sync.retry-delay-ms:2000}") private val retryDelayMs: Long = 0,
) {
    private val json = jacksonObjectMapper()
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * False when the caller failed authentication. Authenticated but irrelevant pushes are accepted and dropped.
     * [credential] is what the caller presented: the secret in the URL for Mercado Livre (it signs
     * nothing), the Authorization header for Shopee (body signature) and OLX (tenant token).
     */
    fun receive(provider: String, credential: String?, rawBody: ByteArray): Boolean {
        if (rawBody.size > MAX_BODY) return false
        val body = String(rawBody, StandardCharsets.UTF_8)
        val payload = parse(body)
        val targets = when (provider) {
            "ml" -> if (validMercadoLivreSecret(credential)) mercadoLivreConnections(payload) ?: return false else return false
            "shopee" -> if (shopee.validPush(credential, body)) shopeeConnections(payload) else return false
            "olx" -> olxConnections(oauth.tenantOfWebhookToken(credential) ?: return false, payload)
            else -> return false
        }
        val hash = sha256(body)
        for (connection in targets) {
            val key = "webhook:$provider:$hash"
            if (events.existsByTenantIdAndIdempotencyKey(connection.tenantId, key)) continue
            val event = RawEventEntity(
                tenantId = connection.tenantId,
                connectionId = connection.id,
                source = provider,
                eventType = PENDING,
                idempotencyKey = key,
                payloadHash = hash,
                payload = body,
            )
            try {
                events.saveAndFlush(event)
            } catch (_: DataIntegrityViolationException) {
                continue
            }
            executor.execute { handle(event.id) }
        }
        return true
    }

    @EventListener(ApplicationReadyEvent::class)
    fun replayPending() {
        events.findByEventType(PENDING).forEach { event -> executor.execute { handle(event.id) } }
    }

    fun handle(eventId: UUID) {
        val event = events.findById(eventId).orElse(null)?.takeIf { it.eventType == PENDING } ?: return
        var settled = false
        for (attempt in 1..ATTEMPTS) {
            settled = try {
                deliver(event)
            } catch (ex: Exception) {
                log.warn("Webhook {} from {} failed (attempt {}): {}", event.id, event.source, attempt, ex.message)
                false
            }
            if (settled || attempt == ATTEMPTS) break
            Thread.sleep(retryDelayMs * attempt)
        }
        // Ends as failed, never pending again: a poisoned event must not replay on every start.
        event.eventType = if (settled) DONE else FAILED
        events.save(event)
    }

    private fun deliver(event: RawEventEntity): Boolean {
        val connection = event.connectionId?.let { connections.findById(it).orElse(null) }
        if (connection == null || connection.status != "ativa") return true
        val sale = saleOf(connection, parse(event.payload)) ?: return true
        return sync.process(sale)
    }

    /** Fails closed: with no secret configured every Mercado Livre push is refused. */
    private fun validMercadoLivreSecret(presented: String?): Boolean {
        val expected = properties.mercadoLivre.webhookSecret
        if (expected.isBlank() || presented == null) return false
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), presented.toByteArray(StandardCharsets.UTF_8))
    }

    private fun mercadoLivreConnections(payload: Map<String, Any?>): List<ConnectionEntity>? {
        val clientId = properties.mercadoLivre.clientId
        if (clientId.isBlank() || CatalogJson.text(payload, "application_id") != clientId) return null
        if (CatalogJson.text(payload, "topic") != "orders_v2") return emptyList()
        if (!ML_ORDER.matches(CatalogJson.text(payload, "resource").orEmpty())) return emptyList()
        val seller = CatalogJson.text(payload, "user_id") ?: return emptyList()
        return connections.findByExternalIdAndStatus("ml", "ativa").filter { it.sellerExternalId == seller }
    }

    private fun shopeeConnections(payload: Map<String, Any?>): List<ConnectionEntity> {
        if (CatalogJson.intValue(payload, "code", -1) != SHOPEE_ORDER_STATUS) return emptyList()
        val shop = CatalogJson.text(payload, "shop_id") ?: return emptyList()
        return connections.findByExternalIdAndStatus("shopee", "ativa").filter { connection ->
            // Stored as "<shop>" or, for merchant grants, "m:<merchant>:<shop>".
            connection.sellerExternalId.orEmpty().substringAfterLast(':') == shop
        }
    }

    private fun olxConnections(tenantId: UUID, payload: Map<String, Any?>): List<ConnectionEntity> {
        if (CatalogJson.text(payload, "topic") != "AD_STATUS") return emptyList()
        return listOfNotNull(connections.findByTenantIdAndExternalId(tenantId, "olx")?.takeIf { it.status == "ativa" })
    }

    private fun saleOf(connection: ConnectionEntity, payload: Map<String, Any?>): SaleEvent? = when (connection.externalId) {
        "ml" -> mercadoLivreSale(connection, payload)
        "shopee" -> shopeeSale(connection, payload)
        else -> olxSale(connection, payload)
    }

    private fun mercadoLivreSale(connection: ConnectionEntity, payload: Map<String, Any?>): SaleEvent? {
        val orderId = ML_ORDER.matchEntire(CatalogJson.text(payload, "resource").orEmpty())?.groupValues?.get(1) ?: return null
        val access = leaser.lease(connection.id)
        val base = properties.mercadoLivre.apiUrl.trimEnd('/')
        val response = http.requestJson("GET", "$base/orders/$orderId", access.token)
        if (response.status >= 400) throw UnprocessableException(apiMessage("stocksync.order", response.status))
        val order = CatalogJson.map(response.body)
        if (CatalogJson.text(CatalogJson.child(order, "seller"), "id") != connection.sellerExternalId) return null
        val cancelled = when (CatalogJson.text(order, "status")) {
            "paid" -> false
            "cancelled" -> true
            else -> return null
        }
        val lines = CatalogJson.maps(order["order_items"]).map { row ->
            val item = CatalogJson.child(row, "item")
            SaleLine(
                sku = CatalogJson.text(item, "seller_sku") ?: CatalogJson.text(item, "seller_custom_field") ?: "",
                quantity = CatalogJson.intValue(row, "quantity"),
                title = CatalogJson.text(item, "title") ?: "",
            )
        }
        return event(connection, orderId, CatalogJson.instant(order, "date_created"), cancelled, lines)
    }

    private fun shopeeSale(connection: ConnectionEntity, payload: Map<String, Any?>): SaleEvent? {
        val orderSn = CatalogJson.text(CatalogJson.child(payload, "data"), "ordersn") ?: return null
        val access = leaser.lease(connection.id)
        val detail = shopee.get(
            access,
            "/api/v2/order/get_order_detail",
            mapOf("order_sn_list" to orderSn, "response_optional_fields" to "item_list"),
        )
        val order = CatalogJson.maps(detail["order_list"]).firstOrNull() ?: return null
        val lines = CatalogJson.maps(order["item_list"]).map { item ->
            SaleLine(
                sku = CatalogJson.text(item, "model_sku") ?: CatalogJson.text(item, "item_sku") ?: "",
                quantity = CatalogJson.intValue(item, "model_quantity_purchased"),
                title = CatalogJson.text(item, "item_name") ?: "",
            )
        }
        // Shopee reserves stock when the order is created, so every status but CANCELLED is a sale.
        val cancelled = CatalogJson.text(order, "order_status") == "CANCELLED"
        return event(connection, orderSn, CatalogJson.instant(order, "create_time"), cancelled, lines)
    }

    private fun olxSale(connection: ConnectionEntity, payload: Map<String, Any?>): SaleEvent? {
        val ad = CatalogJson.child(CatalogJson.child(payload, "data"), "ad")
        if (CatalogJson.text(ad, "status") !in OLX_SOLD) return null
        val adId = CatalogJson.text(ad, "id") ?: return null
        return SaleEvent(connection.tenantId, "olx", "ad:$adId", Instant.now(), false, listOf(SaleLine(adId, 1, adId)))
    }

    /** Orders created before the connection started syncing stock are history, not new sales. */
    private fun event(
        connection: ConnectionEntity,
        orderId: String,
        createdAt: Instant?,
        cancelled: Boolean,
        lines: List<SaleLine>,
    ): SaleEvent? {
        val since = connection.stockSyncSince
        if (since != null && createdAt != null && createdAt.isBefore(since)) return null
        return SaleEvent(connection.tenantId, connection.externalId, orderId, createdAt ?: Instant.now(), cancelled, lines)
    }

    private fun parse(body: String): Map<String, Any?> =
        runCatching { json.readValue<Map<String, Any?>>(body) }.getOrDefault(emptyMap())

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_BODY = 64 * 1024
        private const val ATTEMPTS = 3
        private const val PENDING = "webhook.pending"
        private const val DONE = "webhook.done"
        private const val FAILED = "webhook.failed"
        private const val SHOPEE_ORDER_STATUS = 3
        private val ML_ORDER = Regex("^/orders/(\\d{1,20})$")
        private val OLX_SOLD = setOf("sold", "deleted_sold")
    }
}
