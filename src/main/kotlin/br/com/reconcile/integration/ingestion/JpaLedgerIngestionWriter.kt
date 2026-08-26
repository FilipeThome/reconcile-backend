package br.com.reconcile.integration.ingestion

import br.com.reconcile.fiscal.infra.InvoiceEntity
import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.ledger.infra.FeeEntity
import br.com.reconcile.ledger.infra.FeeRepository
import br.com.reconcile.ledger.infra.OrderEntity
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.ledger.infra.PaymentEntity
import br.com.reconcile.ledger.infra.PaymentRepository
import br.com.reconcile.ledger.infra.RefundEntity
import br.com.reconcile.ledger.infra.RefundRepository
import br.com.reconcile.ledger.infra.SettlementEntity
import br.com.reconcile.ledger.infra.SettlementRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.util.UUID

@Component
class JpaLedgerIngestionWriter(
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val fees: FeeRepository,
    private val refunds: RefundRepository,
    private val invoices: InvoiceRepository,
    private val settlements: SettlementRepository,
) : LedgerIngestionWriter {
    @Transactional
    override fun persist(tenantId: UUID, sales: List<CanonicalSale>): IngestionResult {
        var orderCount = 0
        var paymentCount = 0
        var feeCount = 0
        var refundCount = 0
        var invoiceCount = 0
        sales.forEach { sale ->
            val order = upsertOrder(tenantId, sale.order)
            orderCount++
            val firstPayment = sale.payments.map { payment ->
                paymentCount++
                upsertPayment(tenantId, order.id, payment)
            }.firstOrNull()
            sale.fees.forEach {
                feeCount++
                upsertFee(tenantId, order.id, it)
            }
            sale.refunds.forEach {
                refundCount++
                upsertRefund(tenantId, order.id, it)
            }
            sale.invoice?.let {
                invoiceCount++
                upsertInvoice(tenantId, order.id, it)
            }
            if (firstPayment != null) {
                upsertSettlement(tenantId, order.id, firstPayment.id, sale)
            }
        }
        return IngestionResult(orderCount, paymentCount, feeCount, refundCount, invoiceCount)
    }

    private fun upsertOrder(tenantId: UUID, canonical: CanonicalOrder): OrderEntity {
        val existing = orders.findByTenantIdAndExternalId(tenantId, canonical.externalId)
        val entity = existing ?: OrderEntity(tenantId = tenantId, externalId = canonical.externalId)
        entity.orderedAt = canonical.orderedAt
        entity.buyerName = canonical.buyerName.take(160)
        entity.marketplace = canonical.marketplace
        entity.items = canonical.items
        entity.grossAmount = canonical.grossAmount
        entity.expectedNet = canonical.expectedNet
        entity.actualNet = canonical.actualNet
        entity.status = canonical.status
        if (existing == null) entity.reconciliationStatus = "pendente_dados"
        return orders.save(entity)
    }

    private fun upsertPayment(tenantId: UUID, orderId: UUID, canonical: CanonicalPayment): PaymentEntity {
        val existing = payments.findByTenantIdAndExternalId(tenantId, canonical.externalId)
        val entity = existing ?: PaymentEntity(tenantId = tenantId, externalId = canonical.externalId, orderId = orderId)
        entity.orderId = orderId
        entity.paidAt = canonical.paidAt
        entity.gateway = canonical.gateway
        entity.method = canonical.method
        entity.grossAmount = canonical.grossAmount
        entity.fees = canonical.fees
        entity.netAmount = canonical.netAmount
        entity.status = canonical.status
        if (existing == null) entity.reconciliationStatus = "pendente_dados"
        return payments.save(entity)
    }

    private fun upsertFee(tenantId: UUID, orderId: UUID, canonical: CanonicalFee) {
        val existing = fees.findByTenantIdAndExternalId(tenantId, canonical.externalId)
        val entity = existing ?: FeeEntity(tenantId = tenantId, externalId = canonical.externalId, orderId = orderId)
        entity.orderId = orderId
        entity.feeType = canonical.type.take(80)
        entity.expectedAmount = canonical.expected
        entity.actualAmount = canonical.actual
        entity.source = canonical.source.take(80)
        entity.occurredAt = canonical.occurredAt
        fees.save(entity)
    }

    private fun upsertRefund(tenantId: UUID, orderId: UUID, canonical: CanonicalRefund) {
        val existing = refunds.findByTenantIdAndExternalId(tenantId, canonical.externalId)
        val entity = existing ?: RefundEntity(tenantId = tenantId, externalId = canonical.externalId, orderId = orderId)
        entity.orderId = orderId
        entity.refundType = canonical.type
        entity.occurredAt = canonical.occurredAt
        entity.amount = canonical.amount
        entity.status = canonical.status.take(120)
        refunds.save(entity)
    }

    private fun upsertInvoice(tenantId: UUID, orderId: UUID, canonical: CanonicalInvoice) {
        val existing = invoices.findByTenantIdAndExternalId(tenantId, canonical.externalId)
            ?: invoices.findByTenantIdAndOrderId(tenantId, orderId)
        val entity = existing ?: InvoiceEntity(tenantId = tenantId, externalId = canonical.externalId, orderId = orderId)
        entity.orderId = orderId
        entity.externalId = canonical.externalId
        entity.documentType = canonical.type
        entity.number = canonical.number
        entity.accessKey = canonical.accessKey
        entity.issuedAt = canonical.issuedAt
        entity.totalAmount = canonical.total
        entity.status = canonical.status
        invoices.save(entity)
    }

    private fun upsertSettlement(tenantId: UUID, orderId: UUID, paymentId: UUID, sale: CanonicalSale) {
        if (settlements.findByTenantIdAndOrderId(tenantId, orderId) != null) return
        val settledOn = sale.order.orderedAt.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(14)
        settlements.save(
            SettlementEntity(
                tenantId = tenantId,
                externalId = CatalogJson.clipped("SET-${sale.order.externalId}"),
                paymentId = paymentId,
                orderId = orderId,
                settledOn = settledOn,
                expectedNet = sale.order.expectedNet,
                actualNet = sale.order.actualNet,
                status = if (sale.order.actualNet == null) "pendente_liquidacao" else "pendente_dados",
            ),
        )
    }
}
