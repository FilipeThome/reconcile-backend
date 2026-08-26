package br.com.reconcile.integration.ingestion

import java.util.UUID

interface LedgerIngestionWriter {
    fun persist(tenantId: UUID, sales: List<CanonicalSale>): IngestionResult
}
