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
import br.com.reconcile.shared.api.PeriodFilter
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

    fun listOrders(filter: PeriodFilter): List<Map<String, Any?>> {
        val tenant = tenantId()
        val paymentsByOrder = payments.findByTenantIdOrderByPaidAtDesc(tenant).groupBy { it.orderId }
        return orders.findByTenantIdOrderByOrderedAtDesc(tenant)
            .filter { order ->
                filter.matchesInstant(order.orderedAt) &&
                    filter.matchesMarketplace(order.marketplace) &&
                    filter.matchesGateway(paymentsByOrder[order.id]?.firstOrNull()?.gateway)
            }
            .map { orderDto(it) }
    }

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
            "payment" to payment?.let {
                mapOf(
                    "id" to it.externalId,
                    "orderId" to order.externalId,
                    "date" to it.paidAt.toString(),
                    "gateway" to it.gateway,
                    "method" to it.method,
                    "grossAmount" to it.grossAmount,
                    "fees" to it.fees,
                    "netAmount" to it.netAmount,
                    "status" to it.status,
                    "reconciliation" to it.reconciliationStatus,
                )
            },
            "settlement" to settlement?.let {
                mapOf(
                    "id" to it.externalId,
                    "orderId" to order.externalId,
                    "paymentId" to (payment?.externalId ?: ""),
                    "date" to it.settledOn.toString(),
                    "expectedNet" to it.expectedNet,
                    "actualNet" to it.actualNet,
                    "status" to it.status,
                    "snapshotId" to it.snapshotExternalId,
                )
            },
            "payout" to payout?.let {
                mapOf(
                    "id" to it.externalId,
                    "gateway" to it.gateway,
                    "date" to it.paidAt.toString(),
                    "grossAmount" to it.grossAmount,
                    "fees" to it.fees,
                    "netAmount" to it.netAmount,
                    "status" to it.status,
                    "reconciliation" to it.reconciliationStatus,
                )
            },
            "invoice" to invoice?.let {
                mapOf(
                    "id" to it.externalId,
                    "orderId" to order.externalId,
                    "type" to it.documentType,
                    "number" to it.number,
                    "key" to it.accessKey,
                    "issuedAt" to it.issuedAt?.toString(),
                    "total" to it.totalAmount,
                    "status" to it.status,
                    "cbs" to it.cbsAmount,
                    "ibs" to it.ibsAmount,
                )
            },
            "snapshot" to snapshot?.let {
                mapOf(
                    "id" to it.externalId,
                    "orderId" to order.externalId,
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

    fun listPayments(filter: PeriodFilter) = payments.findByTenantIdOrderByPaidAtDesc(tenantId()).mapNotNull { pay ->
        val order = orders.findById(pay.orderId).orElse(null)
        if (!filter.matchesInstant(pay.paidAt) || !filter.matchesGateway(pay.gateway) || !filter.matchesMarketplace(order?.marketplace)) {
            return@mapNotNull null
        }
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

    fun listSettlements(filter: PeriodFilter) = settlements.findByTenantId(tenantId()).mapNotNull { set ->
        val order = orders.findById(set.orderId).orElse(null)
        val payment = payments.findById(set.paymentId).orElse(null)
        if (!filter.matchesDate(set.settledOn) || !filter.matchesMarketplace(order?.marketplace) || !filter.matchesGateway(payment?.gateway)) {
            return@mapNotNull null
        }
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

    fun listPayouts(filter: PeriodFilter) = payouts.findByTenantIdOrderByPaidAtDesc(tenantId()).mapNotNull { payout ->
        if (!filter.matchesInstant(payout.paidAt) || !filter.matchesGateway(payout.gateway)) return@mapNotNull null
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

    fun listFees(filter: PeriodFilter) = fees.findByTenantId(tenantId()).mapNotNull { fee ->
        val order = orders.findById(fee.orderId).orElse(null)
        if (!filter.matchesInstant(fee.occurredAt) || !filter.matchesMarketplace(order?.marketplace)) return@mapNotNull null
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

    fun listRefunds(filter: PeriodFilter) = refunds.findByTenantId(tenantId()).mapNotNull { refund ->
        val order = refund.orderId?.let { orders.findById(it).orElse(null) }
        if (!filter.matchesInstant(refund.occurredAt) || !filter.matchesMarketplace(order?.marketplace)) return@mapNotNull null
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
