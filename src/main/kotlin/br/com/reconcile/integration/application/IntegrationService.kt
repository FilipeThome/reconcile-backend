package br.com.reconcile.integration.application

import br.com.reconcile.identity.infra.SellerAccountEntity
import br.com.reconcile.identity.infra.SellerAccountRepository
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.infra.IntegrationSyncEntity
import br.com.reconcile.integration.infra.IntegrationSyncRepository
import br.com.reconcile.integration.infra.RawEventEntity
import br.com.reconcile.integration.infra.RawEventRepository
import br.com.reconcile.integration.ingestion.MarketplaceIngestionService
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.integration.oauth.OAuthTokens
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class IntegrationService(
    private val connections: ConnectionRepository,
    private val syncs: IntegrationSyncRepository,
    private val events: RawEventRepository,
    private val audit: AuditService,
    private val oauth: MarketplaceOAuthService,
    private val sellers: SellerAccountRepository,
    private val ingestion: MarketplaceIngestionService,
) {
    fun list() = connections.findByTenantId(currentUser().tenantId).map {
        val hint = oauth.configuredHint(it.externalId)
        val detail = when {
            it.status == "ativa" -> it.detail ?: it.sellerExternalId?.let { id -> "OAuth seller $id" }
            hint != null -> hint
            else -> it.detail
        }
        mapOf(
            "id" to it.externalId,
            "name" to it.name,
            "kind" to it.kind,
            "status" to it.status,
            "detail" to detail,
            "lastSync" to it.lastSyncAt?.toString(),
            "oauth" to oauth.isOAuthProvider(it.externalId),
        )
    }

    @Transactional
    fun connect(externalId: String): Map<String, Any?> {
        val user = currentUser()
        val connection = requireConnection(user.tenantId, externalId)
        if (oauth.isOAuthProvider(externalId)) {
            val start = oauth.authorizationUrl(externalId, user.tenantId, user.userId)
            connection.detail = "Aguardando autorização OAuth"
            connections.save(connection)
            audit.record(user.tenantId, user.displayName, "integration.oauth.start", "integration", externalId)
            return mapOf(
                "id" to connection.externalId,
                "status" to connection.status,
                "authorizationUrl" to start.authorizationUrl,
            )
        }
        setStatus(connection, "ativa")
        return mapOf("id" to connection.externalId, "status" to "ativa")
    }

    @Transactional
    fun disconnect(externalId: String): Map<String, Any?> {
        val connection = requireConnection(currentUser().tenantId, externalId)
        connection.accessToken = null
        connection.refreshToken = null
        connection.tokenExpiresAt = null
        connection.sellerExternalId = null
        setStatus(connection, "disponivel")
        return mapOf("id" to connection.externalId, "status" to "disponivel")
    }

    @Transactional
    fun activateFromOAuth(tokens: OAuthTokens) {
        val connection = requireConnection(tokens.tenantId, tokens.provider)
        connection.status = "ativa"
        connection.accessToken = tokens.accessToken
        connection.refreshToken = tokens.refreshToken
        connection.tokenExpiresAt = tokens.expiresAt
        connection.sellerExternalId = tokens.sellerExternalId
        connection.detail = tokens.sellerLabel ?: "OAuth seller ${tokens.sellerExternalId}"
        connections.save(connection)
        upsertSeller(tokens)
        audit.record(tokens.tenantId, "oauth", "integration.oauth.connected", "integration", tokens.provider)
    }

    @Transactional
    fun backfill(externalId: String): Map<String, Any?> {
        val user = currentUser()
        val connection = requireConnection(user.tenantId, externalId)
        if (ingestion.supports(externalId)) {
            val result = ingestion.ingest(connection)
            syncs.save(IntegrationSyncEntity(tenantId = user.tenantId, connectionId = connection.id, kind = "backfill", status = "done"))
            audit.record(user.tenantId, user.displayName, "integration.backfill", "integration", externalId)
            return mapOf("id" to externalId, "status" to "done") + result.toMap()
        }
        val key = "backfill:$externalId:${Instant.now().epochSecond / 60}"
        if (!events.existsByTenantIdAndIdempotencyKey(user.tenantId, key)) {
            val payload = """{"type":"backfill","connection":"$externalId"}"""
            events.save(
                RawEventEntity(
                    tenantId = user.tenantId,
                    connectionId = connection.id,
                    source = connection.name,
                    eventType = "backfill",
                    idempotencyKey = key,
                    payloadHash = sha256(payload),
                    payload = payload,
                ),
            )
        }
        connection.lastSyncAt = Instant.now()
        connections.save(connection)
        syncs.save(IntegrationSyncEntity(tenantId = user.tenantId, connectionId = connection.id, kind = "backfill", status = "done"))
        audit.record(user.tenantId, user.displayName, "integration.backfill", "integration", externalId)
        return mapOf("id" to externalId, "status" to "done", "orders" to 0, "payments" to 0, "fees" to 0, "refunds" to 0, "invoices" to 0)
    }

    private fun setStatus(connection: br.com.reconcile.integration.infra.ConnectionEntity, status: String) {
        val user = currentUser()
        connection.status = status
        if (status == "ativa") connection.lastSyncAt = Instant.now()
        connections.save(connection)
        audit.record(user.tenantId, user.displayName, "integration.status", "integration", connection.externalId)
    }

    private fun requireConnection(tenantId: UUID, externalId: String) =
        connections.findByTenantIdAndExternalId(tenantId, externalId)
            ?: throw NotFoundException("Integração $externalId não encontrada")

    private fun upsertSeller(tokens: OAuthTokens) {
        val marketplace = when (tokens.provider) {
            "ml" -> "Mercado Livre"
            "amazon" -> "Amazon"
            else -> "Shopee"
        }
        val existing = sellers.findByTenantIdAndMarketplace(tokens.tenantId, marketplace)
        if (existing == null) {
            sellers.save(
                SellerAccountEntity(
                    tenantId = tokens.tenantId,
                    marketplace = marketplace,
                    sellerExternalId = tokens.sellerExternalId,
                ),
            )
        } else {
            existing.sellerExternalId = tokens.sellerExternalId
            sellers.save(existing)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
