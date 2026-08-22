package br.com.reconcile.ledger.api

import br.com.reconcile.ledger.application.LedgerQueryService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
class LedgerControllers(private val ledger: LedgerQueryService) {
    @GetMapping("/orders")
    fun orders() = ledger.listOrders()

    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String) = ledger.getOrder(id)

    @GetMapping("/payments")
    fun payments() = ledger.listPayments()

    @GetMapping("/settlements")
    fun settlements() = ledger.listSettlements()

    @GetMapping("/payouts")
    fun payouts() = ledger.listPayouts()

    @GetMapping("/fees")
    fun fees() = ledger.listFees()

    @GetMapping("/refunds")
    fun refunds() = ledger.listRefunds()
}
