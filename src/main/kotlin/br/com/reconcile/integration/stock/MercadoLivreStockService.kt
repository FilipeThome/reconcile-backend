package br.com.reconcile.integration.stock

import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.security.AuthUser
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class StockConnectionTokenWriter(private val connections: ConnectionRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persist(connection: ConnectionEntity) {
        connections.saveAndFlush(connection)
    }
}

@Service
class MercadoLivreStockService(
    private val connections: ConnectionRepository,
    private val oauth: MarketplaceOAuthService,
    private val client: MercadoLivreStockClient,
    private val audit: AuditService,
    private val tokens: StockConnectionTokenWriter,
) {
    fun list(query: String?, status: String?, offset: Int, limit: Int): StockPage {
        val access = access()
        return client.list(
            access.token,
            access.sellerId,
            query?.trim()?.take(80)?.ifBlank { null },
            normalizeStatus(status),
            offset.coerceAtLeast(0).coerceAtMost(1000),
            limit.coerceIn(1, 20),
        )
    }

    fun get(itemId: String): StockItem {
        val access = access()
        return client.get(access.token, access.sellerId, itemId)
    }

    fun update(
        itemId: String,
        availableQuantity: Int,
        variationId: Long?,
        storeId: String?,
        networkNodeId: String? = null,
    ): StockItem {
        val access = access()
        val cleanStore = storeId?.trim()?.ifBlank { null }
        val cleanNode = networkNodeId?.trim()?.ifBlank { null }
        val updated = client.update(
            access.token,
            access.sellerId,
            itemId,
            availableQuantity,
            variationId,
            cleanStore,
            cleanNode,
        )
        audit.record(
            access.user.tenantId,
            access.user.displayName,
            "integration.stock.update",
            "integration",
            itemId,
            auditDetails(availableQuantity, variationId, cleanStore, cleanNode),
        )
        return updated
    }

    private fun access(): Access {
        val user = currentUser()
        val connection = connections.findByTenantIdAndExternalId(user.tenantId, "ml")
            ?: throw NotFoundException("Integração Mercado Livre não encontrada")
        if (connection.status != "ativa") {
            throw UnprocessableException("Conecte o Mercado Livre antes de consultar o estoque.")
        }
        val token = connection.accessToken ?: throw UnprocessableException("Token OAuth ausente. Reconecte o Mercado Livre.")
        val lease = oauth.ensureAccessToken(
            "ml",
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
        val sellerId = client.sellerId(lease.accessToken, connection.sellerExternalId.orEmpty())
        return Access(lease.accessToken, sellerId, user)
    }

    private fun normalizeStatus(status: String?): String? = when (val clean = status?.trim()?.lowercase()) {
        null, "", "all" -> null
        "active", "paused", "closed", "under_review", "inactive" -> clean
        else -> throw UnprocessableException("Status de anúncio inválido.")
    }

    private fun auditDetails(quantity: Int, variationId: Long?, storeId: String?, networkNodeId: String?): String {
        fun json(value: String?) = if (value == null) "null" else "\"$value\""
        return """{"quantity":$quantity,"variationId":${variationId ?: "null"},"storeId":${json(storeId)},"networkNodeId":${json(networkNodeId)}}"""
    }

    private class Access(val token: String, val sellerId: String, val user: AuthUser)
}
