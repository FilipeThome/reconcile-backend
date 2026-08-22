package br.com.reconcile.integration.application

import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.infra.IntegrationSyncEntity
import br.com.reconcile.integration.infra.IntegrationSyncRepository
import br.com.reconcile.integration.infra.RawEventEntity
import br.com.reconcile.integration.infra.RawEventRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

@Service
class IntegrationService(
    private val connections: ConnectionRepository,
    private val syncs: IntegrationSyncRepository,
    private val events: RawEventRepository,
    private val audit: AuditService,
) {
    fun list() = connections.findByTenantId(currentUser().tenantId).map {
        mapOf(
            "id" to it.externalId,
            "name" to it.name,
            "kind" to it.kind,
            "status" to it.status,
            "detail" to it.detail,
            "lastSync" to it.lastSyncAt?.toString(),
        )
    }

    @Transactional
    fun connect(externalId: String) = setStatus(externalId, "ativa")

    @Transactional
    fun disconnect(externalId: String) = setStatus(externalId, "disponivel")

    @Transactional
    fun backfill(externalId: String) {
        val user = currentUser()
        val connection = connections.findByTenantIdAndExternalId(user.tenantId, externalId)
            ?: throw NotFoundException("Integração $externalId não encontrada")
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
    }

    private fun setStatus(externalId: String, status: String) {
        val user = currentUser()
        val connection = connections.findByTenantIdAndExternalId(user.tenantId, externalId)
            ?: throw NotFoundException("Integração $externalId não encontrada")
        connection.status = status
        if (status == "ativa") connection.lastSyncAt = Instant.now()
        connections.save(connection)
        audit.record(user.tenantId, user.displayName, "integration.status", "integration", externalId)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
