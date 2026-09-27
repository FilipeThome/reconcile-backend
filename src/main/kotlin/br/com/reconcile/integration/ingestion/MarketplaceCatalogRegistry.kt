package br.com.reconcile.integration.ingestion

import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import org.springframework.stereotype.Component

@Component
class MarketplaceCatalogRegistry(catalogs: List<MarketplaceCatalog>) {
    private val byId = catalogs.associateBy { it.providerId().lowercase() }

    fun supports(providerId: String) = byId.containsKey(providerId.lowercase())

    fun require(providerId: String): MarketplaceCatalog =
        byId[providerId.lowercase()]
            ?: throw UnprocessableException(apiMessage("ingest.unsupported", providerId))
}
