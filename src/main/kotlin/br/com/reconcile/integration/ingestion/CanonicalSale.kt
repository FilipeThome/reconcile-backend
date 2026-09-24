package br.com.reconcile.integration.ingestion

import java.math.BigDecimal
import java.time.Instant

data class CanonicalSale(
    val order: CanonicalOrder,
    val payments: List<CanonicalPayment>,
    val fees: List<CanonicalFee>,
    val refunds: List<CanonicalRefund>,
    val invoice: CanonicalInvoice?,
)

data class CanonicalOrder(
    val externalId: String,
    val orderedAt: Instant,
    val buyerName: String,
    val marketplace: String,
    val items: Int,
    val grossAmount: BigDecimal,
    val expectedNet: BigDecimal,
    val actualNet: BigDecimal?,
    val status: String,
)

data class CanonicalPayment(
    val externalId: String,
    val paidAt: Instant,
    val gateway: String,
    val method: String,
    val grossAmount: BigDecimal,
    val fees: BigDecimal,
    val netAmount: BigDecimal,
    val status: String,
)

data class CanonicalFee(
    val externalId: String,
    val type: String,
    val expected: BigDecimal,
    val actual: BigDecimal,
    val source: String,
    val occurredAt: Instant,
)

data class CanonicalRefund(
    val externalId: String,
    val type: String,
    val amount: BigDecimal,
    val occurredAt: Instant,
    val status: String,
)

data class CanonicalInvoice(
    val externalId: String,
    val type: String,
    val number: String,
    val accessKey: String?,
    val issuedAt: Instant?,
    val total: BigDecimal,
    val status: String,
)
