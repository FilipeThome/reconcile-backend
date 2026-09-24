package br.com.reconcile.integration.ingestion.shopee

import br.com.reconcile.integration.ingestion.CanonicalFee
import br.com.reconcile.integration.ingestion.CanonicalOrder
import br.com.reconcile.integration.ingestion.CanonicalPayment
import br.com.reconcile.integration.ingestion.CanonicalRefund
import br.com.reconcile.integration.ingestion.CanonicalSale
import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.shared.domain.Money
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ShopeeSaleMapper {
    fun toSale(order: Map<String, Any?>): CanonicalSale? {
        val id = CatalogJson.text(order, "order_sn") ?: return null
        val orderedAt = CatalogJson.instant(order, "create_time") ?: Instant.now()
        val items = CatalogJson.maps(order["item_list"])
        val gross = CatalogJson.decimal(order, "total_amount")
        val escrow = CatalogJson.decimal(order, "escrow_amount").takeIf { it > Money.ZERO }
            ?: CatalogJson.decimal(CatalogJson.child(order, "escrow_detail"), "escrow_amount").takeIf { it > Money.ZERO }
        val commission = CatalogJson.decimal(order, "commission_fee").takeIf { it > Money.ZERO }
            ?: CatalogJson.decimal(CatalogJson.child(order, "escrow_detail"), "commission_fee")
        val expectedNet = escrow ?: Money.scale(gross - commission)
        val status = orderStatus(CatalogJson.text(order, "order_status"))
        val paidAt = CatalogJson.instant(order, "pay_time") ?: orderedAt
        return CanonicalSale(
            order = CanonicalOrder(
                externalId = CatalogJson.clipped(id),
                orderedAt = orderedAt,
                buyerName = CatalogJson.text(order, "buyer_username") ?: "Comprador Shopee",
                marketplace = "Shopee",
                items = items.sumOf { CatalogJson.intValue(it, "model_quantity_purchased", 1).coerceAtLeast(1) }.coerceAtLeast(1),
                grossAmount = gross,
                expectedNet = expectedNet,
                actualNet = escrow,
                status = status,
            ),
            payments = listOf(
                CanonicalPayment(
                    externalId = CatalogJson.clipped("$id-pay"),
                    paidAt = paidAt,
                    gateway = "Shopee",
                    method = paymentMethod(CatalogJson.text(order, "payment_method")),
                    grossAmount = gross,
                    fees = commission,
                    netAmount = expectedNet,
                    status = if (status == "cancelado") "estornado" else "aprovado",
                ),
            ),
            fees = if (commission > Money.ZERO) {
                listOf(
                    CanonicalFee(
                        externalId = CatalogJson.clipped("$id:commission"),
                        type = "Comissão marketplace",
                        expected = commission,
                        actual = commission,
                        source = "Shopee",
                        occurredAt = paidAt,
                    ),
                )
            } else {
                emptyList()
            },
            refunds = if (status == "cancelado") {
                listOf(
                    CanonicalRefund(
                        externalId = CatalogJson.clipped("$id-cancel"),
                        type = "estorno",
                        amount = gross,
                        occurredAt = CatalogJson.instant(order, "update_time") ?: orderedAt,
                        status = CatalogJson.text(order, "order_status") ?: "cancelado",
                    ),
                )
            } else {
                emptyList()
            },
            invoice = null,
        )
    }

    private fun orderStatus(raw: String?) = when (raw?.uppercase()) {
        "CANCELLED", "IN_CANCEL", "TO_RETURN" -> "cancelado"
        "UNPAID" -> "pendente"
        else -> "pago"
    }

    private fun paymentMethod(raw: String?) = when {
        raw.isNullOrBlank() -> "Pix"
        raw.contains("CREDIT", ignoreCase = true) || raw.contains("CARD", ignoreCase = true) -> "Cartão"
        raw.contains("COD", ignoreCase = true) || raw.contains("BANK", ignoreCase = true) -> "Boleto"
        else -> "Pix"
    }
}
