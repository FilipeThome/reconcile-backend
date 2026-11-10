package br.com.reconcile.integration.stocksync

import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.integration.stock.MercadoLivreStockClient
import br.com.reconcile.integration.stock.StockConnectionTokenWriter
import br.com.reconcile.integration.stock.StockItem
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One sellable unit on a marketplace: an item, or one variation of it. */
data class StockListing(
    val itemId: String,
    val variationId: String?,
    val title: String,
    /** Null when the marketplace did not return a readable quantity. */
    val quantity: Int?,
    val locationId: String? = null,
)

class ConnectionAccess(val provider: String, val token: String, val sellerId: String)

/** Stock of one marketplace, addressed by the seller's own SKU. */
interface MarketplaceStockPort {
    fun providerId(): String

    /** Listings whose SKU equals [sku] exactly (case-insensitive). Never a fuzzy match. */
    fun find(access: ConnectionAccess, sku: String): List<StockListing>

    fun setQuantity(access: ConnectionAccess, listing: StockListing, quantity: Int)

    /** False for a marketplace with one ad per product and no quantity: the ad goes only when the SKU is sold out. */
    fun tracksQuantity(): Boolean = true
}

/**
 * Valid access token for a connection, usable outside a request (webhook threads have no user).
 * Refresh tokens rotate, so the refresh of one connection is serialized and re-reads the row.
 */
@Service
class ConnectionTokenLeaser(
    private val connections: ConnectionRepository,
    private val oauth: MarketplaceOAuthService,
    private val tokens: StockConnectionTokenWriter,
) {
    private val locks = ConcurrentHashMap<UUID, Any>()

    fun lease(connectionId: UUID): ConnectionAccess = synchronized(locks.computeIfAbsent(connectionId) { Any() }) {
        val connection: ConnectionEntity? = connections.findById(connectionId).orElse(null)
        val token = connection?.takeIf { it.status == "ativa" }?.accessToken
            ?: throw UnprocessableException(apiMessage("stocksync.connection"))
        val lease = oauth.ensureAccessToken(
            connection.externalId,
            token,
            connection.refreshToken,
            connection.tokenExpiresAt,
            connection.sellerExternalId,
        )
        if (lease.rotated) {
            connection.accessToken = lease.accessToken
            connection.refreshToken = lease.refreshToken
            connection.tokenExpiresAt = lease.expiresAt
            tokens.persist(connection)
        }
        ConnectionAccess(connection.externalId, lease.accessToken, connection.sellerExternalId.orEmpty())
    }
}

@Component
class MercadoLivreStockPort(private val client: MercadoLivreStockClient) : MarketplaceStockPort {
    override fun providerId() = "ml"

    override fun find(access: ConnectionAccess, sku: String): List<StockListing> =
        client.findBySku(access.token, access.sellerId, sku).flatMap { item ->
            val variations = item.variations.filter { it.sku.equals(sku.trim(), ignoreCase = true) }
            if (variations.isNotEmpty()) {
                variations.map { StockListing(item.id, it.id.toString(), "${item.title} · ${it.label}", it.availableQuantity) }
            } else {
                listOf(StockListing(item.id, null, item.title, writableQuantity(item)))
            }
        }

    /**
     * The quantity [setQuantity] will overwrite. With distributed stock the item total also counts
     * Full units, and writing "total - sold" into the seller's own location would inflate it.
     */
    private fun writableQuantity(item: StockItem): Int? {
        if (!item.stockAvailable) return null
        if (item.locations.isEmpty()) return item.availableQuantity
        return item.locations.filter { it.editable }.singleOrNull()?.takeIf { it.quantityKnown }?.quantity
    }

    override fun setQuantity(access: ConnectionAccess, listing: StockListing, quantity: Int) {
        client.update(access.token, access.sellerId, listing.itemId, quantity, listing.variationId?.toLongOrNull(), null)
    }
}
