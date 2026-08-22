package br.com.reconcile.shared.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "audit_logs")
class AuditLogEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    var actor: String = "",
    var action: String = "",
    @Column(name = "entity_type") var entityType: String = "",
    @Column(name = "entity_id") var entityId: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    var details: String = "{}",
    @Column(name = "created_at") var createdAt: Instant = Instant.now(),
)

interface AuditLogRepository : JpaRepository<AuditLogEntity, UUID>

@Service
class AuditService(private val logs: AuditLogRepository) {
    fun record(tenantId: UUID, actor: String, action: String, entityType: String, entityId: String, details: String = "{}") {
        logs.save(
            AuditLogEntity(
                tenantId = tenantId,
                actor = actor,
                action = action,
                entityType = entityType,
                entityId = entityId,
                details = details,
            ),
        )
    }
}
