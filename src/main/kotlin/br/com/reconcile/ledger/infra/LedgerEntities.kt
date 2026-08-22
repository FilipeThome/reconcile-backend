package br.com.reconcile.ledger.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "orders")
class OrderEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "ordered_at") var orderedAt: Instant = Instant.now(),
    @Column(name = "buyer_name") var buyerName: String = "",
    var marketplace: String = "",
    var items: Int = 0,
    @Column(name = "gross_amount") var grossAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "expected_net") var expectedNet: BigDecimal = BigDecimal.ZERO,
    @Column(name = "actual_net") var actualNet: BigDecimal? = null,
    var status: String = "pago",
    @Column(name = "reconciliation_status") var reconciliationStatus: String = "pendente_dados",
    @Version var version: Long = 0,
)

@Entity
@Table(name = "payments")
class PaymentEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "order_id") var orderId: UUID = UUID.randomUUID(),
    @Column(name = "paid_at") var paidAt: Instant = Instant.now(),
    var gateway: String = "",
    var method: String = "",
    @Column(name = "gross_amount") var grossAmount: BigDecimal = BigDecimal.ZERO,
    var fees: BigDecimal = BigDecimal.ZERO,
    @Column(name = "net_amount") var netAmount: BigDecimal = BigDecimal.ZERO,
    var status: String = "aprovado",
    @Column(name = "reconciliation_status") var reconciliationStatus: String = "pendente_dados",
    @Version var version: Long = 0,
)

@Entity
@Table(name = "settlements")
class SettlementEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "payment_id") var paymentId: UUID = UUID.randomUUID(),
    @Column(name = "order_id") var orderId: UUID = UUID.randomUUID(),
    @Column(name = "settled_on") var settledOn: LocalDate = LocalDate.now(),
    @Column(name = "expected_net") var expectedNet: BigDecimal = BigDecimal.ZERO,
    @Column(name = "actual_net") var actualNet: BigDecimal? = null,
    var status: String = "pendente_liquidacao",
    @Column(name = "snapshot_external_id") var snapshotExternalId: String? = null,
    @Version var version: Long = 0,
)

@Entity
@Table(name = "payouts")
class PayoutEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    var gateway: String = "",
    @Column(name = "paid_at") var paidAt: Instant = Instant.now(),
    @Column(name = "gross_amount") var grossAmount: BigDecimal = BigDecimal.ZERO,
    var fees: BigDecimal = BigDecimal.ZERO,
    @Column(name = "net_amount") var netAmount: BigDecimal = BigDecimal.ZERO,
    var status: String = "previsto",
    @Column(name = "reconciliation_status") var reconciliationStatus: String = "pendente_liquidacao",
    @Version var version: Long = 0,
)

data class PayoutOrderId(
    var payoutId: UUID = UUID.randomUUID(),
    var orderId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "payout_orders")
@IdClass(PayoutOrderId::class)
class PayoutOrderEntity(
    @Id @Column(name = "payout_id") var payoutId: UUID = UUID.randomUUID(),
    @Id @Column(name = "order_id") var orderId: UUID = UUID.randomUUID(),
)

@Entity
@Table(name = "fees")
class FeeEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "order_id") var orderId: UUID = UUID.randomUUID(),
    @Column(name = "fee_type") var feeType: String = "",
    @Column(name = "expected_amount") var expectedAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "actual_amount") var actualAmount: BigDecimal = BigDecimal.ZERO,
    var source: String = "",
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.now(),
)

@Entity
@Table(name = "refunds")
class RefundEntity(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id") var tenantId: UUID = UUID.randomUUID(),
    @Column(name = "external_id") var externalId: String = "",
    @Column(name = "order_id") var orderId: UUID? = null,
    @Column(name = "refund_type") var refundType: String = "estorno",
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.now(),
    var amount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "reflected_in_settlement") var reflectedInSettlement: Boolean = false,
    var status: String = "",
)
