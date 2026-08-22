package br.com.reconcile.integration.api

import br.com.reconcile.integration.application.IntegrationService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/integrations")
class IntegrationController(private val service: IntegrationService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun list() = service.list()

    @PostMapping("/{id}/connect")
    @PreAuthorize("hasRole('ADMIN')")
    fun connect(@PathVariable id: String) = service.connect(id)

    @PostMapping("/{id}/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    fun disconnect(@PathVariable id: String) = service.disconnect(id)

    @PostMapping("/{id}/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    fun backfill(@PathVariable id: String) = service.backfill(id)
}
