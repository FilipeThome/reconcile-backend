package br.com.reconcile.fiscal.api

import br.com.reconcile.fiscal.infra.FiscalAdapter
import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.shared.security.currentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/invoices")
@PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
class InvoiceController(
    private val invoices: InvoiceRepository,
    private val orders: OrderRepository,
    private val adapter: FiscalAdapter,
) {
    @GetMapping
    fun list(): Map<String, Any> {
        val tenant = currentUser().tenantId
        val docs = invoices.findByTenantId(tenant).map { invoice ->
            val order = orders.findById(invoice.orderId).orElse(null)
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
        val pending = orders.findByTenantIdOrderByOrderedAtDesc(tenant)
            .filter { invoices.findByTenantIdAndOrderId(tenant, it.id) == null }
            .map { it.externalId }
        return mapOf("documents" to docs, "pendingOrders" to pending)
    }
}
