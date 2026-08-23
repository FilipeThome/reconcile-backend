package br.com.reconcile.reconciliation.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reconciliation_rules")
class RuleEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    var name: String = "",
    var description: String = "",
    @Column(name = "rule_type") var ruleType: String = "financeira",
    var active: Boolean = true,
)

@Entity
@Table(name = "calculation_snapshots")
class SnapshotEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "order_id") var orderId: UUID = UUID.randomUUID(),
    @Column(name = "engine_version") var engineVersion: String = "sce-v1.4",
    @Column(name = "gross_amount") var grossAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "expected_amount") var expectedAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "actual_amount") var actualAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "difference_amount") var differenceAmount: BigDecimal = BigDecimal.ZERO,
    @JdbcTypeCode(SqlTypes.JSON)
    var components: String = "[]",
)

@Entity
@Table(name = "reconciliation_cases")
class CaseEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "opened_at") var openedAt: Instant = Instant.now(),
    @Column(name = "order_external_id") var orderExternalId: String = "",
    @Column(name = "payment_external_id") var paymentExternalId: String? = null,
    @Column(name = "settlement_external_id") var settlementExternalId: String? = null,
    @Column(name = "payout_external_id") var payoutExternalId: String? = null,
    @Column(name = "invoice_external_id") var invoiceExternalId: String? = null,
    var cause: String = "",
    var description: String = "",
    var amount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "recoverable_amount") var recoverableAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "confirmed_loss_amount") var confirmedLossAmount: BigDecimal = BigDecimal.ZERO,
    var impact: String = "aguardando",
    var status: String = "aberta",
    var priority: String = "media",
    @Column(name = "owner_name") var ownerName: String = "",
    var confidence: BigDecimal = BigDecimal.ZERO,
    @Column(name = "suggested_action") var suggestedAction: String = "",
    @Version var version: Long = 0,
)

@Entity
@Table(name = "case_comments")
class CaseCommentEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "case_id") var caseId: UUID = UUID.randomUUID(),
    var author: String = "",
    var body: String = "",
    @Column(name = "created_at") var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "case_timeline")
class CaseTimelineEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "case_id") var caseId: UUID = UUID.randomUUID(),
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.now(),
    var title: String = "",
    var detail: String = "",
    var tone: String = "neutral",
)

interface RuleRepository : JpaRepository<RuleEntity, UUID> {
    fun findByTenantId(tenantId: UUID): List<RuleEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): RuleEntity?
}

interface SnapshotRepository : JpaRepository<SnapshotEntity, UUID> {
    fun findByTenantIdAndOrderId(tenantId: UUID, orderId: UUID): SnapshotEntity?
    fun findByTenantId(tenantId: UUID): List<SnapshotEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): SnapshotEntity?
}

interface CaseRepository : JpaRepository<CaseEntity, UUID> {
    fun findByTenantIdOrderByOpenedAtDesc(tenantId: UUID): List<CaseEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): CaseEntity?
    fun findByTenantIdAndOrderExternalId(tenantId: UUID, orderExternalId: String): CaseEntity?
}

interface CaseCommentRepository : JpaRepository<CaseCommentEntity, UUID> {
    fun findByCaseIdOrderByCreatedAtAsc(caseId: UUID): List<CaseCommentEntity>
}

interface CaseTimelineRepository : JpaRepository<CaseTimelineEntity, UUID> {
    fun findByCaseIdOrderByOccurredAtAsc(caseId: UUID): List<CaseTimelineEntity>
}
