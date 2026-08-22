package br.com.reconcile.closing.api

import br.com.reconcile.closing.application.ClosingService
import br.com.reconcile.closing.application.ReportService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ClosingControllers(
    private val closings: ClosingService,
    private val reports: ReportService,
) {
    @GetMapping("/closings")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun list() = closings.list()

    @GetMapping("/closings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun get(@PathVariable id: String) = closings.get(id)

    @PostMapping("/closings/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    fun close(@PathVariable id: String) = closings.close(id)

    @PostMapping("/reports/{type}/export")
    @PreAuthorize("hasAnyRole('ADMIN','CONTADOR')")
    fun export(@PathVariable type: String): ResponseEntity<String> {
        val result = reports.export(type)
        val filename = (result["filename"] ?: "export.csv").replace(Regex("[^A-Za-z0-9._-]"), "_")
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(result["csv"])
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun history() = reports.history()
}
