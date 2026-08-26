package br.com.reconcile.integration.ingestion

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class IngestionWindow(val from: Instant, val to: Instant) {
    companion object {
        fun lastDays(days: Long): IngestionWindow {
            val to = Instant.now()
            return IngestionWindow(to.minus(days, ChronoUnit.DAYS), to)
        }
    }
}

data class CatalogCredentials(
    val tenantId: UUID,
    val sellerExternalId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
)

data class CatalogSnapshot(val sales: List<CanonicalSale>)

data class IngestionResult(
    val orders: Int,
    val payments: Int,
    val fees: Int,
    val refunds: Int,
    val invoices: Int,
) {
    fun toMap() = mapOf(
        "orders" to orders,
        "payments" to payments,
        "fees" to fees,
        "refunds" to refunds,
        "invoices" to invoices,
    )
}

interface MarketplaceCatalog {
    fun providerId(): String
    fun pull(credentials: CatalogCredentials, window: IngestionWindow): CatalogSnapshot
}
