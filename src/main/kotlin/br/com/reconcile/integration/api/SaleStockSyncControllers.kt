package br.com.reconcile.integration.api

import br.com.reconcile.integration.stocksync.SaleStockSyncService
import br.com.reconcile.integration.stocksync.SaleWebhookService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Public callbacks of the marketplaces. Each provider authenticates in [SaleWebhookService.receive]. */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
class MarketplaceWebhookController(private val webhooks: SaleWebhookService) {
    @PostMapping("/{provider}", "/{provider}/{secret}")
    fun receive(
        @PathVariable provider: String,
        @PathVariable(required = false) secret: String?,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        // Read at most one byte past the limit: an unauthenticated caller must not make the server buffer a large body.
        val body = request.inputStream.readNBytes(SaleWebhookService.MAX_BODY + 1)
        if (body.size > SaleWebhookService.MAX_BODY) return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build()
        val accepted = webhooks.receive(provider.lowercase(), secret ?: authorization, body)
        return ResponseEntity.status(if (accepted) HttpStatus.OK else HttpStatus.UNAUTHORIZED).build()
    }
}

@RestController
@RequestMapping("/api/v1/integrations/sales")
class SaleAlertController(private val sales: SaleStockSyncService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun list() = sales.list()

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    fun register(@RequestBody body: ManualSaleRequest) = sales.registerManual(body.sku, body.quantity, body.reference)
}

class ManualSaleRequest(
    val sku: String,
    val quantity: Int,
    val reference: String? = null,
)
