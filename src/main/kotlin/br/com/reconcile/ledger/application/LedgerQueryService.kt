package br.com.reconcile.ledger.application

import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.ledger.infra.FeeRepository
import br.com.reconcile.ledger.infra.OrderEntity
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.ledger.infra.PaymentRepository
import br.com.reconcile.ledger.infra.PayoutOrderRepository
import br.com.reconcile.ledger.infra.PayoutRepository
import br.com.reconcile.ledger.infra.RefundRepository
import br.com.reconcile.ledger.infra.SettlementRepository
import br.com.reconcile.reconciliation.infra.CaseRepository
import br.com.reconcile.reconciliation.infra.SnapshotRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LedgerQueryService(
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val settlements: SettlementRepository,
    private val payouts: PayoutRepository,
    private val payoutOrders: PayoutOrderRepository,
    private val fees: FeeRepository,
    private val refunds: RefundRepository,
    private val invoices: InvoiceRepository,
    private val cases: CaseRepository,
    private val snapshots: SnapshotRepository,
) {
    private fun tenantId(): UUID = currentUser().tenantId

    fun listOrders() = orders.findByTenantIdOrderByOrderedAtDesc(tenantId()).map { orderDto(it) }

    fun getOrder(externalId: String): Map<String, Any?> {
        val order = orders.findByTenantIdAndExternalId(tenantId(), externalId)
            ?: throw NotFoundException("Pedido $externalId não encontrado")
        val payment = payments.findByTenantIdAndOrderId(tenantId(), order.id).firstOrNull()
        val settlement = settlements.findByTenantIdAndOrderId(tenantId(), order.id)
        val payout = payoutOrders.findByOrderId(order.id).firstOrNull()?.let { link ->
            payouts.findById(link.payoutId).orElse(null)
        }
        val invoice = invoices.findByTenantIdAndOrderId(tenantId(), order.id)
        val snapshot = snapshots.findByTenantIdAndOrderId(tenantId(), order.id)
        val case = cases.findByTenantIdAndOrderExternalId(tenantId(), order.externalId)
        return mapOf(
            "id" to order.externalId,
            "date" to order.orderedAt.toString(),
            "buyer" to order.buyerName,
            "marketplace" to order.marketplace,
            "items" to order.items,
            "grossAmount" to order.grossAmount,
            "expectedNet" to order.expectedNet,
            "actualNet" to order.actualNet,
            "status" to order.status,
            "reconciliation" to order.reconciliationStatus,
            "paymentId" to payment?.externalId,
            "settlementId" to settlement?.externalId,
            "payoutId" to payout?.externalId,
            "invoiceId" to invoice?.externalId,
            "caseId" to case?.externalId,
            "snapshot" to snapshot?.let {
                mapOf(
                    "id" to it.externalId,
                    "version" to it.engineVersion,
                    "gross" to it.grossAmount,
                    "expected" to it.expectedAmount,
                    "actual" to it.actualAmount,
                    "difference" to it.differenceAmount,
                    "components" to it.components,
                )
            },
        )
    }

    fun listPayments() = payments.findByTenantIdOrderByPaidAtDesc(tenantId()).map { pay ->
        val order = orders.findById(pay.orderId).orElse(null)
        mapOf(
            "id" to pay.externalId,
            "orderId" to (order?.externalId ?: ""),
            "date" to pay.paidAt.toString(),
            "gateway" to pay.gateway,
            "method" to pay.method,
            "grossAmount" to pay.grossAmount,
            "fees" to pay.fees,
            "netAmount" to pay.netAmount,
            "status" to pay.status,
            "reconciliation" to pay.reconciliationStatus,
        )
    }

    fun listSettlements() = settlements.findByTenantId(tenantId()).map { set ->
        val order = orders.findById(set.orderId).orElse(null)
        val payment = payments.findById(set.paymentId).orElse(null)
        mapOf(
            "id" to set.externalId,
            "orderId" to (order?.externalId ?: ""),
            "paymentId" to (payment?.externalId ?: ""),
            "date" to set.settledOn.toString(),
            "expectedNet" to set.expectedNet,
            "actualNet" to set.actualNet,
            "status" to set.status,
            "snapshotId" to set.snapshotExternalId,
        )
    }

    fun listPayouts() = payouts.findByTenantIdOrderByPaidAtDesc(tenantId()).map { payout ->
        val orderIds = payoutOrders.findByPayoutId(payout.id).mapNotNull { link ->
            orders.findById(link.orderId).orElse(null)?.externalId
        }
        mapOf(
            "id" to payout.externalId,
            "gateway" to payout.gateway,
            "date" to payout.paidAt.toString(),
            "grossAmount" to payout.grossAmount,
            "fees" to payout.fees,
            "netAmount" to payout.netAmount,
            "status" to payout.status,
            "reconciliation" to payout.reconciliationStatus,
            "orderIds" to orderIds,
        )
    }

    fun listFees() = fees.findByTenantId(tenantId()).map { fee ->
        val order = orders.findById(fee.orderId).orElse(null)
        mapOf(
            "id" to fee.externalId,
            "orderId" to (order?.externalId ?: ""),
            "type" to fee.feeType,
            "expected" to fee.expectedAmount,
            "actual" to fee.actualAmount,
            "source" to fee.source,
            "date" to fee.occurredAt.toString(),
        )
    }

    fun listRefunds() = refunds.findByTenantId(tenantId()).map { refund ->
        val order = refund.orderId?.let { orders.findById(it).orElse(null) }
        mapOf(
            "id" to refund.externalId,
            "orderId" to (order?.externalId ?: ""),
            "type" to refund.refundType,
            "date" to refund.occurredAt.toString(),
            "amount" to refund.amount,
            "reflectedInSettlement" to refund.reflectedInSettlement,
            "status" to refund.status,
        )
    }

    private fun orderDto(order: OrderEntity): Map<String, Any?> {
        val payment = payments.findByTenantIdAndOrderId(tenantId(), order.id).firstOrNull()
        val settlement = settlements.findByTenantIdAndOrderId(tenantId(), order.id)
        val payout = payoutOrders.findByOrderId(order.id).firstOrNull()?.let { payouts.findById(it.payoutId).orElse(null) }
        val invoice = invoices.findByTenantIdAndOrderId(tenantId(), order.id)
        val case = cases.findByTenantIdAndOrderExternalId(tenantId(), order.externalId)
        return mapOf(
            "id" to order.externalId,
            "date" to order.orderedAt.toString(),
            "buyer" to order.buyerName,
            "marketplace" to order.marketplace,
            "items" to order.items,
            "grossAmount" to order.grossAmount,
            "expectedNet" to order.expectedNet,
            "actualNet" to order.actualNet,
            "status" to order.status,
            "reconciliation" to order.reconciliationStatus,
            "paymentId" to payment?.externalId,
            "settlementId" to settlement?.externalId,
            "payoutId" to payout?.externalId,
            "invoiceId" to invoice?.externalId,
            "caseId" to case?.externalId,
        )
    }
}
