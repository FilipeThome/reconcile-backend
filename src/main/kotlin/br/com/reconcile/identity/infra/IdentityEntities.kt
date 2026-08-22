package br.com.reconcile.identity.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "tenants")
class TenantEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "external_id", nullable = false) var externalId: String = "",
    var name: String = "",
    @Column(name = "settlement_deadline_days") var settlementDeadlineDays: Int = 14,
    @Column(name = "amount_tolerance") var amountTolerance: BigDecimal = BigDecimal("0.05"),
    @Column(name = "date_tolerance_days") var dateToleranceDays: Int = 2,
    @Column(name = "raw_event_retention_days") var rawEventRetentionDays: Int = 180,
    @Column(name = "invoice_required") var invoiceRequired: Boolean = true,
    @Column(name = "invoice_deadline_hours") var invoiceDeadlineHours: Int = 48,
)

@Entity
@Table(name = "users")
class UserEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID = UUID.randomUUID(),
    var name: String = "",
    var email: String = "",
    @Column(name = "password_hash") var passwordHash: String = "",
    var role: String = "operador",
)

@Entity
@Table(name = "seller_accounts")
class SellerAccountEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id", nullable = false) var tenantId: UUID = UUID.randomUUID(),
    var marketplace: String = "",
    @Column(name = "seller_external_id") var sellerExternalId: String = "",
)
