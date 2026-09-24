package br.com.reconcile.integration.api

import br.com.reconcile.integration.application.IntegrationService
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.shared.api.UnprocessableException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.view.RedirectView

@RestController
@RequestMapping("/api/v1/integrations/oauth")
class MarketplaceOAuthController(
    private val oauth: MarketplaceOAuthService,
    private val integrations: IntegrationService,
) {
    @GetMapping("/{provider}/callback")
    fun callback(
        @PathVariable provider: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(name = "shop_id", required = false) shopId: Long?,
        @RequestParam(name = "main_account_id", required = false) mainAccountId: Long?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false) errorDescription: String?,
        @RequestParam(name = "selling_partner_id", required = false) sellingPartnerId: String? = null,
    ): RedirectView {
        if (!error.isNullOrBlank() || !errorDescription.isNullOrBlank()) {
            return RedirectView(oauth.frontendRedirect(error = error ?: "exchange_failed"))
        }
        return try {
            val tokens = oauth.exchange(provider, code, state, shopId, mainAccountId, sellingPartnerId)
            integrations.activateFromOAuth(tokens)
            RedirectView(oauth.frontendRedirect(connected = tokens.provider))
        } catch (ex: UnprocessableException) {
            RedirectView(oauth.frontendRedirect(error = "exchange_failed"))
        } catch (ex: Exception) {
            RedirectView(oauth.frontendRedirect(error = "exchange_failed"))
        }
    }
}
