package br.com.reconcile.integration.ingestion

import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.shared.api.UnprocessableException
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

@Service
class MarketplaceIngestionService(
    private val catalogs: MarketplaceCatalogRegistry,
    private val writer: LedgerIngestionWriter,
    private val oauth: MarketplaceOAuthService,
    private val connections: ConnectionRepository,
) {
    fun supports(providerId: String) = catalogs.supports(providerId)

    fun ingest(connection: ConnectionEntity): IngestionResult {
        if (connection.status != "ativa") {
            throw UnprocessableException("Conecte ${connection.name} antes do backfill")
        }
        val access = connection.accessToken ?: throw UnprocessableException("Token OAuth ausente. Reconecte ${connection.name}.")
        val lease = oauth.ensureAccessToken(
            connection.externalId,
            access,
            connection.refreshToken,
            connection.tokenExpiresAt,
            connection.sellerExternalId,
        )
        if (lease.rotated) {
            connection.accessToken = lease.accessToken
            connection.refreshToken = lease.refreshToken
            connection.tokenExpiresAt = lease.expiresAt
            lease.sellerExternalId?.let { connection.sellerExternalId = it }
            connections.save(connection)
        }
        val snapshot = try {
            catalogs.require(connection.externalId).pull(
                CatalogCredentials(
                    tenantId = connection.tenantId,
                    sellerExternalId = connection.sellerExternalId.orEmpty(),
                    accessToken = lease.accessToken,
                    refreshToken = lease.refreshToken,
                    expiresAt = lease.expiresAt,
                ),
                IngestionWindow.lastDays(90),
            )
        } catch (ex: RestClientResponseException) {
            throw UnprocessableException("A API do marketplace recusou a consulta (${ex.statusCode.value()})")
        }
        val result = writer.persist(connection.tenantId, snapshot.sales)
        connection.lastSyncAt = Instant.now()
        connections.save(connection)
        return result
    }
}
