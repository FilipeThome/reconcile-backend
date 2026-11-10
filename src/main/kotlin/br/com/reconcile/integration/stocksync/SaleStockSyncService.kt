package br.com.reconcile.integration.stocksync

import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.infra.SaleEventEntity
import br.com.reconcile.integration.infra.SaleEventRepository
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.security.currentUser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class SaleLine(val sku: String, val quantity: Int, val title: String)

data class SaleEvent(
    val tenantId: UUID,
    /** Where the sale happened: a connection external id, or "manual". That marketplace is never a target. */
    val provider: String,
    val orderId: String,
    val occurredAt: Instant,
    val cancelled: Boolean,
    val lines: List<SaleLine>,
)

/** Outcome of the stock change on one listing of another marketplace. */
data class TargetResult(
    val provider: String,
    val itemId: String = "",
    val variationId: String? = null,
    val title: String = "",
    val before: Int? = null,
    val after: Int? = null,
    val ok: Boolean = false,
    val message: String? = null,
    /** The sale was cancelled and this listing got its units back. */
    val restored: Boolean = false,
)

/**
 * A sale of a SKU on one marketplace lowers the stock of the same SKU on every other connected
 * marketplace; cancelling that sale gives back exactly what it took. Changes are relative (never
 * "copy the source quantity") so simultaneous sales on two marketplaces both count.
 *
 * Reading then writing a quantity is serialized per (tenant, SKU) inside this JVM, which is
 * enough for the single backend instance this project deploys.
 */
@Service
class SaleStockSyncService(
    private val sales: SaleEventRepository,
    private val connections: ConnectionRepository,
    private val leaser: ConnectionTokenLeaser,
    private val audit: AuditService,
    ports: List<MarketplaceStockPort>,
) {
    private val ports = ports.associateBy { it.providerId() }
    private val locks = Array(LOCK_STRIPES) { Any() }
    private val json = jacksonObjectMapper()
    private val log = LoggerFactory.getLogger(javaClass)

    /** False when some listing could not be changed yet; processing the same event again retries only those. */
    fun process(event: SaleEvent): Boolean {
        val lines = event.lines
            .filter { it.sku.isNotBlank() && it.quantity > 0 }
            .groupBy { it.sku.trim().lowercase() }
            .map { (_, same) -> SaleLine(same.first().sku.trim().take(120), same.sumOf { it.quantity }, same.first().title) }
        var settled = true
        for (line in lines) {
            val stripe = Math.floorMod("${event.tenantId}:${line.sku.lowercase()}".hashCode(), LOCK_STRIPES)
            synchronized(locks[stripe]) {
                if (!(if (event.cancelled) cancel(event, line) else sell(event, line))) settled = false
            }
        }
        return settled
    }

    fun registerManual(sku: String, quantity: Int, reference: String?): List<Map<String, Any?>> {
        val user = currentUser()
        val cleanSku = sku.trim()
        if (cleanSku.isEmpty() || cleanSku.length > 120) throw UnprocessableException(apiMessage("stocksync.sku"))
        if (quantity !in 1..9_999) throw UnprocessableException(apiMessage("stocksync.quantity"))
        val orderId = reference?.trim()?.take(100)?.ifBlank { null } ?: UUID.randomUUID().toString()
        process(SaleEvent(user.tenantId, "manual", orderId, Instant.now(), false, listOf(SaleLine(cleanSku, quantity, cleanSku))))
        audit.record(user.tenantId, user.displayName, "integration.sale.manual", "sku", cleanSku, """{"quantity":$quantity}""")
        return list().filter { it["provider"] == "manual" && it["orderId"] == orderId }
    }

    fun list(): List<Map<String, Any?>> =
        sales.findTop50ByTenantIdOrderByCreatedAtDesc(currentUser().tenantId).map { row ->
            mapOf(
                "id" to row.id.toString(),
                "provider" to row.provider,
                "orderId" to row.orderExternalId,
                "sku" to row.sku,
                "title" to row.title,
                "quantity" to row.quantity,
                "status" to row.status,
                "occurredAt" to row.occurredAt.toString(),
                "createdAt" to row.createdAt.toString(),
                // The in-flight marker is bookkeeping, not a listing.
                "targets" to decode(row.targets).filter { it != IN_FLIGHT },
            )
        }

    /** True when every listing found for the SKU was changed. */
    private fun sell(event: SaleEvent, line: SaleLine): Boolean {
        val existing = find(event, line)
        if (existing?.status == CANCELLED) return true
        val previous = existing?.let { decode(it.targets) }.orEmpty()
        val done = previous.filter { it.ok }
        // A re-delivered notification only retries what failed; nothing failed means nothing to do.
        if (existing != null && done.size == previous.size) return true
        val row = existing ?: claim(event, line) ?: return true
        val others = connected(event.tenantId).filter { it.externalId != event.provider }
        val lower = { target: TargetResult -> if (done.none { it.sameListing(target) }) -line.quantity else null }
        val results = done.toMutableList()
        // Saved after each marketplace, still carrying the not-ok marker: a crash here re-applies at most
        // the one marketplace that was in flight, never the ones already recorded.
        val record: (ConnectionEntity) -> Unit = { connection ->
            results += change(connection, line.sku, lower)
            row.targets = json.writeValueAsString(results + IN_FLIGHT)
            sales.save(row)
        }
        others.filter { port(it).tracksQuantity() }.forEach(record)
        // A marketplace without quantities (one ad per product) only loses the ad when the SKU is sold out everywhere.
        if (soldOut(event, line.sku, results)) others.filterNot { port(it).tracksQuantity() }.forEach(record)
        row.targets = json.writeValueAsString(results)
        sales.save(row)
        if (existing == null) {
            log.info("Sale {} on {}: sku {} x{}, {} listing(s) updated", event.orderId, event.provider, line.sku, line.quantity, results.count { it.ok })
            audit.record(event.tenantId, event.provider, "integration.sale", "sku", line.sku, """{"quantity":${line.quantity}}""")
        }
        return results.all { it.ok }
    }

    /** True once every listing the sale lowered has its units back. Until then the row stays sold and a retry resumes. */
    private fun cancel(event: SaleEvent, line: SaleLine): Boolean {
        val row = find(event, line)?.takeIf { it.status == SOLD } ?: return true
        val recorded = decode(row.targets)
        // A removed ad cannot be brought back from here, so only quantities are owed.
        val owed = recorded.filter { it.ok && !it.restored && ports[it.provider]?.tracksQuantity() == true }
        val attempts = connected(event.tenantId)
            .filter { connection -> owed.any { it.provider == connection.externalId } }
            .flatMap { connection ->
                // Give back what each listing actually lost: a decrement clamped at zero took less than the order quantity.
                change(connection, line.sku) { target ->
                    owed.find { it.sameListing(target) }?.let { (it.before ?: 0) - (it.after ?: 0) }
                }
            }
        val returned = attempts.filter { it.ok }.map { it.copy(restored = true) }
        val stillOwed = owed.filter { debt -> returned.none { it.sameListing(debt) } }
        row.targets = json.writeValueAsString(recorded.filter { it.ok && it !in owed } + returned + stillOwed)
        if (stillOwed.isEmpty()) row.status = CANCELLED
        sales.save(row)
        if (stillOwed.isNotEmpty()) {
            log.warn("Cancel {} on {}: sku {} still owed on {} listing(s)", event.orderId, event.provider, line.sku, stillOwed.size)
            return false
        }
        audit.record(event.tenantId, event.provider, "integration.sale.cancelled", "sku", line.sku, """{"quantity":${row.quantity}}""")
        return true
    }

    /** Inserting the row is the idempotency claim: a concurrent duplicate loses on the unique key. */
    private fun claim(event: SaleEvent, line: SaleLine): SaleEventEntity? = try {
        sales.saveAndFlush(
            SaleEventEntity(
                tenantId = event.tenantId,
                provider = event.provider,
                orderExternalId = event.orderId.take(120),
                sku = line.sku,
                title = line.title.take(200),
                quantity = line.quantity,
                status = SOLD,
                // Not-ok marker: if the process dies before the targets are written, a re-delivery retries.
                targets = json.writeValueAsString(listOf(IN_FLIGHT)),
                occurredAt = event.occurredAt,
            ),
        )
    } catch (_: DataIntegrityViolationException) {
        null
    }

    /**
     * Applies [delta] to every listing of [sku] on one connection; a null delta leaves the listing alone.
     * A failure becomes a not-ok result so one marketplace being down never blocks the others.
     */
    private fun change(connection: ConnectionEntity, sku: String, delta: (TargetResult) -> Int?): List<TargetResult> = try {
        val access = leaser.lease(connection.id)
        port(connection).find(access, sku).mapNotNull { listing ->
            val target = TargetResult(connection.externalId, listing.itemId, listing.variationId, listing.title, listing.quantity)
            val amount = delta(target) ?: return@mapNotNull null
            try {
                val before = listing.quantity ?: throw UnprocessableException(apiMessage("stocksync.quantityUnknown"))
                val after = (before + amount).coerceAtLeast(0)
                port(connection).setQuantity(access, listing, after)
                target.copy(after = after, ok = true)
            } catch (ex: Exception) {
                target.copy(message = reason(ex))
            }
        }
    } catch (ex: Exception) {
        listOf(TargetResult(connection.externalId, message = reason(ex)))
    }

    /**
     * True when no marketplace that tracks quantities still has units of [sku]. An unknown quantity
     * counts as "still in stock": removing an ad on a guess is worse than leaving it.
     */
    private fun soldOut(event: SaleEvent, sku: String, lowered: List<TargetResult>): Boolean {
        val elsewhere = lowered.filter { ports[it.provider]?.tracksQuantity() == true }.map { if (it.ok) it.after else it.before }
        val source = connected(event.tenantId)
            .filter { it.externalId == event.provider && port(it).tracksQuantity() }
            .flatMap { connection ->
                try {
                    port(connection).find(leaser.lease(connection.id), sku).map { it.quantity }
                } catch (_: Exception) {
                    listOf(null)
                }
            }
        return (elsewhere + source).all { it == 0 }
    }

    private fun connected(tenantId: UUID) =
        connections.findByTenantId(tenantId).filter { it.status == "ativa" && ports.containsKey(it.externalId) }

    private fun port(connection: ConnectionEntity) = ports.getValue(connection.externalId)

    private fun find(event: SaleEvent, line: SaleLine) =
        sales.findByTenantIdAndProviderAndOrderExternalIdAndSku(event.tenantId, event.provider, event.orderId.take(120), line.sku)

    private fun decode(raw: String): List<TargetResult> = json.readValue(raw)

    private fun reason(ex: Exception) = (ex.message ?: ex.javaClass.simpleName).take(300)

    private fun TargetResult.sameListing(other: TargetResult) =
        provider == other.provider && itemId == other.itemId && variationId == other.variationId

    private companion object {
        const val SOLD = "vendido"
        const val CANCELLED = "cancelado"
        const val LOCK_STRIPES = 64
        val IN_FLIGHT = TargetResult("pendente")
    }
}
