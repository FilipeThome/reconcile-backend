package br.com.reconcile.ledger.api

import br.com.reconcile.ledger.application.LedgerQueryService
import br.com.reconcile.shared.api.PeriodFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
class LedgerControllers(private val ledger: LedgerQueryService) {
    @GetMapping("/orders")
    fun orders(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = ledger.listOrders(PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String) = ledger.getOrder(id)

    @GetMapping("/payments")
    fun payments(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = ledger.listPayments(PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/settlements")
    fun settlements(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = ledger.listSettlements(PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/payouts")
    fun payouts(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = ledger.listPayouts(PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/fees")
    fun fees(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = ledger.listFees(PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/refunds")
    fun refunds(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = ledger.listRefunds(PeriodFilter.of(from, to, marketplace, gateway))
}
