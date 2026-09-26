package br.com.reconcile.integration.api

import br.com.reconcile.integration.stock.MercadoLivreStockService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/integrations/ml/stock")
class MercadoLivreStockController(private val stock: MercadoLivreStockService) {
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "20") limit: Int,
    ) = stock.list(q, status, offset, limit)

    @GetMapping("/{itemId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR','CONTADOR')")
    fun get(@PathVariable itemId: String) = stock.get(itemId)

    @PutMapping("/{itemId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    fun update(@PathVariable itemId: String, @RequestBody body: UpdateStockRequest) =
        stock.update(itemId, body.availableQuantity, body.variationId, body.storeId, body.networkNodeId)
}

class UpdateStockRequest(
    val availableQuantity: Int,
    val variationId: Long? = null,
    val storeId: String? = null,
    val networkNodeId: String? = null,
)
