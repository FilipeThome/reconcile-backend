package br.com.reconcile.closing.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "closings")
class ClosingEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    var period: String = "",
    var label: String = "",
    var status: String = "open",
    @Column(name = "closed_at") var closedAt: Instant? = null,
    @Column(name = "orders_count") var ordersCount: Int = 0,
    @Column(name = "gross_amount") var grossAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "expected_amount") var expectedAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "received_amount") var receivedAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "fees_amount") var feesAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "refunds_amount") var refundsAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "open_diff_amount") var openDiffAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "pending_invoices") var pendingInvoices: Int = 0,
    @Version var version: Long = 0,
)

@Entity
@Table(name = "closing_deltas")
class ClosingDeltaEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "closing_id") var closingId: UUID = UUID.randomUUID(),
    var reason: String = "",
    var amount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "created_at") var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "exports")
class ExportEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "report_type") var reportType: String = "",
    var filename: String = "",
    @Column(name = "created_by") var createdBy: String = "",
    @Column(name = "created_at") var createdAt: Instant = Instant.now(),
)

interface ClosingRepository : JpaRepository<ClosingEntity, UUID> {
    fun findByTenantIdOrderByPeriodDesc(tenantId: UUID): List<ClosingEntity>
    fun findByTenantIdAndExternalId(tenantId: UUID, externalId: String): ClosingEntity?
}

interface ClosingDeltaRepository : JpaRepository<ClosingDeltaEntity, UUID> {
    fun findByClosingId(closingId: UUID): List<ClosingDeltaEntity>
}

interface ExportRepository : JpaRepository<ExportEntity, UUID> {
    fun findByTenantIdOrderByCreatedAtDesc(tenantId: UUID): List<ExportEntity>
}
