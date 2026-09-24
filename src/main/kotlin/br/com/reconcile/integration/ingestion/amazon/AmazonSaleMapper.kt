package br.com.reconcile.integration.ingestion.amazon

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
class AmazonSaleMapper {
    fun toSale(order: Map<String, Any?>, items: List<Map<String, Any?>> = emptyList()): CanonicalSale? {
        val id = CatalogJson.text(order, "AmazonOrderId") ?: return null
        val orderedAt = CatalogJson.instant(order, "PurchaseDate") ?: Instant.now()
        val lines = items.ifEmpty { CatalogJson.maps(order["OrderItems"]) }
        val gross = money(order["OrderTotal"]).takeIf { it > Money.ZERO }
            ?: lines.fold(Money.ZERO) { acc, item -> acc + money(item["ItemPrice"]) }
        val commission = lines.fold(Money.ZERO) { acc, item ->
            acc + CatalogJson.decimal(item, "ReferralFee") + CatalogJson.decimal(item, "Commission")
        }
        val shipping = lines.fold(Money.ZERO) { acc, item -> acc + money(item["ShippingPrice"]) }
        val expectedNet = Money.scale(gross - commission - shipping)
        val status = orderStatus(CatalogJson.text(order, "OrderStatus"))
        val paidAt = CatalogJson.instant(order, "LastUpdateDate") ?: orderedAt
        val itemCount = lines.sumOf { CatalogJson.intValue(it, "QuantityOrdered", 1).coerceAtLeast(0) }
            .takeIf { it > 0 }
            ?: (CatalogJson.intValue(order, "NumberOfItemsShipped", 0) + CatalogJson.intValue(order, "NumberOfItemsUnshipped", 0))
        return CanonicalSale(
            order = CanonicalOrder(
                externalId = CatalogJson.clipped(id),
                orderedAt = orderedAt,
                buyerName = buyerName(order),
                marketplace = "Amazon",
                items = itemCount.coerceAtLeast(1),
                grossAmount = gross,
                expectedNet = expectedNet,
                actualNet = if (status == "pago") expectedNet else null,
                status = status,
            ),
            payments = listOf(
                CanonicalPayment(
                    externalId = CatalogJson.clipped("$id-pay"),
                    paidAt = paidAt,
                    gateway = "Amazon",
                    method = paymentMethod(CatalogJson.text(order, "PaymentMethod")),
                    grossAmount = gross,
                    fees = Money.scale(commission + shipping),
                    netAmount = expectedNet,
                    status = if (status == "cancelado") "estornado" else "aprovado",
                ),
            ),
            fees = fees(id, paidAt, commission, shipping),
            refunds = if (status == "cancelado") {
                listOf(
                    CanonicalRefund(
                        externalId = CatalogJson.clipped("$id-cancel"),
                        type = "estorno",
                        amount = gross,
                        occurredAt = paidAt,
                        status = CatalogJson.text(order, "OrderStatus") ?: "Canceled",
                    ),
                )
            } else {
                emptyList()
            },
            invoice = null,
        )
    }

    private fun fees(id: String, at: Instant, commission: java.math.BigDecimal, shipping: java.math.BigDecimal) =
        buildList {
            if (commission > Money.ZERO) {
                add(CanonicalFee(CatalogJson.clipped("$id:referral"), "Comissão marketplace", commission, commission, "Amazon", at))
            }
            if (shipping > Money.ZERO) {
                add(CanonicalFee(CatalogJson.clipped("$id:shipping"), "Frete", shipping, shipping, "Amazon", at))
            }
        }

    private fun buyerName(order: Map<String, Any?>): String {
        val buyer = CatalogJson.child(order, "BuyerInfo")
        return CatalogJson.text(buyer, "BuyerName")
            ?: CatalogJson.text(buyer, "BuyerEmail")
            ?: CatalogJson.text(order, "BuyerName")
            ?: "Comprador Amazon"
    }

    private fun money(node: Any?): java.math.BigDecimal {
        val map = node as? Map<*, *> ?: return Money.ZERO
        @Suppress("UNCHECKED_CAST")
        return CatalogJson.decimal(map as Map<String, Any?>, "Amount")
    }

    private fun orderStatus(raw: String?) = when (raw?.uppercase()) {
        "CANCELED", "CANCELLED" -> "cancelado"
        "PENDING", "PENDINGAVAILABILITY", "UNSHIPPED" -> "pendente"
        else -> "pago"
    }

    private fun paymentMethod(raw: String?) = when {
        raw.isNullOrBlank() -> "Cartão"
        raw.contains("COD", ignoreCase = true) || raw.contains("CVS", ignoreCase = true) -> "Boleto"
        raw.contains("OTHER", ignoreCase = true) || raw.contains("PIX", ignoreCase = true) -> "Pix"
        else -> "Cartão"
    }
}
