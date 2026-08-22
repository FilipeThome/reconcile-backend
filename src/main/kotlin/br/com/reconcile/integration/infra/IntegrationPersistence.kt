package br.com.reconcile.integration.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "marketplace_connections")
class ConnectionEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    var name: String = "",
    var kind: String = "",
    var status: String = "disponivel",
    var detail: String? = null,
    @Column(name = "last_sync_at") var lastSyncAt: Instant? = null,
    @Version var version: Long = 0,
)

@Entity
@Table(name = "raw_events")
class RawEventEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "connection_id") var connectionId: UUID? = null,
    var source: String = "",
    @Column(name = "event_type") var eventType: String = "",
    @Column(name = "idempotency_key") var idempotencyKey: String = "",
    @Column(name = "payload_hash") var payloadHash: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    var payload: String = "{}",
)

@Entity
@Table(name = "integration_syncs")
class IntegrationSyncEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "connection_id") var connectionId: UUID = UUID.randomUUID(),
    var kind: String = "backfill",
    var status: String = "started",
    @Column(name = "started_at") var startedAt: Instant = Instant.now(),
    @Column(name = "finished_at") var finishedAt: Instant? = Instant.now(),
)

interface ConnectionRepository : JpaRepository<ConnectionEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<ConnectionEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): ConnectionEntity?
}

interface RawEventRepository : JpaRepository<RawEventEntity, UUID> {
    fun existsByTenantIdAndIdempotencyKey(tenantId: UUID, idempotencyKey: String): Boolean
}

interface IntegrationSyncRepository : JpaRepository<IntegrationSyncEntity, UUID>
