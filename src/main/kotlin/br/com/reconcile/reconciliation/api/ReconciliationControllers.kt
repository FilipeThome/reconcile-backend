package br.com.reconcile.reconciliation.api

import br.com.reconcile.reconciliation.application.CaseService
import br.com.reconcile.reconciliation.application.DashboardService
import br.com.reconcile.reconciliation.application.EngineService
import br.com.reconcile.reconciliation.application.RuleQueryService
import br.com.reconcile.reconciliation.application.SnapshotQueryService
import br.com.reconcile.shared.api.PeriodFilter
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class StatusRequest(
    @field:NotBlank
    @field:Pattern(regexp = "aberta|em_analise|resolvida|ignorada")
    val status: String,
)

data class CommentRequest(
    @field:NotBlank
    @field:Size(max = 2000)
    val text: String,
)

@RestController
@RequestMapping("/api/v1")
class ReconciliationControllers(
    private val cases: CaseService,
    private val rules: RuleQueryService,
    private val engine: EngineService,
    private val dashboard: DashboardService,
    private val snapshots: SnapshotQueryService,
) {
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun dashboard(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = dashboard.dashboard(PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/cases")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) marketplace: String?,
        @RequestParam(required = false) gateway: String?,
    ) = cases.list(status, PeriodFilter.of(from, to, marketplace, gateway))

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun get(@PathVariable id: String) = cases.get(id)

    @PatchMapping("/cases/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    fun status(@PathVariable id: String, @Valid @RequestBody body: StatusRequest) = cases.updateStatus(id, body.status)

    @PostMapping("/cases/{id}/comments")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    fun comment(@PathVariable id: String, @Valid @RequestBody body: CommentRequest) = cases.addComment(id, body.text.trim())

    @PostMapping("/cases/{id}/reprocess")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    fun reprocess(@PathVariable id: String): Map<String, Any?> {
        val orderId = cases.get(id)["orderId"] as String
        return engine.reprocess(orderId)
    }

    @GetMapping("/engine")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun engine() = engine.overview()

    @GetMapping("/snapshots")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun snapshots() = snapshots.list()

    @GetMapping("/snapshots/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun snapshot(@PathVariable id: String) = snapshots.get(id)

    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun rules() = rules.list()

    @PatchMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    fun toggleRule(@PathVariable id: String) = rules.toggle(id)
}
