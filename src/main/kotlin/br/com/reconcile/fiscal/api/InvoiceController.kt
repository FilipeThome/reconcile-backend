package br.com.reconcile.fiscal.api

import br.com.reconcile.fiscal.infra.FiscalAdapter
import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.ledger.infra.PaymentRepository
import br.com.reconcile.shared.api.PeriodFilter
import br.com.reconcile.shared.security.currentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/invoices")
@PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
class InvoiceController(
    private val invoices: InvoiceRepository,
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val adapter: FiscalAdapter,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ): Map<String, Any> {
        val tenant = currentUser().tenantId
        val filter = PeriodFilter.of(from, to, marketplace, gateway)
        val orderMap = orders.findByTenantIdOrderByOrderedAtDesc(tenant).associateBy { it.id }
        val gatewayByOrder = payments.findByTenantIdOrderByPaidAtDesc(tenant)
            .groupBy { it.orderId }
            .mapValues { (_, rows) -> rows.firstOrNull()?.gateway }
        val docs = invoices.findByTenantId(tenant).mapNotNull { invoice ->
            val order = orderMap[invoice.orderId]
            if (!filter.matchesInstant(invoice.issuedAt ?: order?.orderedAt) ||
                !filter.matchesMarketplace(order?.marketplace) ||
                !filter.matchesGateway(gatewayByOrder[invoice.orderId])
            ) {
                return@mapNotNull null
            }
            mapOf(
                "id" to invoice.externalId,
                "orderId" to (order?.externalId ?: ""),
                "type" to invoice.documentType,
                "number" to invoice.number,
                "key" to invoice.accessKey,
                "issuedAt" to invoice.issuedAt?.toString(),
                "total" to invoice.totalAmount,
                "status" to invoice.status,
                "providerStatus" to adapter.consultStatus(invoice.accessKey),
                "cbs" to invoice.cbsAmount,
                "ibs" to invoice.ibsAmount,
            )
        }
        val pending = orderMap.values
            .filter {
                filter.matchesInstant(it.orderedAt) &&
                    filter.matchesMarketplace(it.marketplace) &&
                    filter.matchesGateway(gatewayByOrder[it.id])
            }
            .filter { invoices.findByTenantIdAndOrderId(tenant, it.id) == null }
            .map { it.externalId }
        return mapOf("documents" to docs, "pendingOrders" to pending)
    }
}
