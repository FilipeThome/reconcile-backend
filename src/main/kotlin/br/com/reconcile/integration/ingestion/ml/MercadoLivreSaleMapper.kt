package br.com.reconcile.integration.ingestion.ml

import br.com.reconcile.integration.ingestion.CanonicalFee
import br.com.reconcile.integration.ingestion.CanonicalInvoice
import br.com.reconcile.integration.ingestion.CanonicalOrder
import br.com.reconcile.integration.ingestion.CanonicalPayment
import br.com.reconcile.integration.ingestion.CanonicalRefund
import br.com.reconcile.integration.ingestion.CanonicalSale
import br.com.reconcile.integration.ingestion.CatalogJson
import br.com.reconcile.shared.domain.Money
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class MercadoLivreSaleMapper {
    fun toSale(order: Map<String, Any?>, fiscal: Map<String, Any?> = emptyMap()): CanonicalSale? {
        val id = CatalogJson.text(order, "id") ?: return null
        val orderedAt = CatalogJson.instant(order, "date_created") ?: Instant.now()
        val items = CatalogJson.maps(order["order_items"])
        val payments = CatalogJson.maps(order["payments"])
        val gross = CatalogJson.decimal(order, "total_amount").takeIf { it > Money.ZERO }
            ?: CatalogJson.decimal(order, "paid_amount")
        val saleFees = items.fold(Money.ZERO) { acc, item ->
            val unitFee = CatalogJson.decimal(item, "sale_fee")
            val qty = CatalogJson.intValue(item, "quantity", 1).coerceAtLeast(1)
            acc + unitFee.multiply(BigDecimal.valueOf(qty.toLong()))
        }
        val paymentFees = payments.fold(Money.ZERO) { acc, payment ->
            acc + CatalogJson.maps(payment["fee_details"]).fold(Money.ZERO) { inner, fee ->
                inner + CatalogJson.decimal(fee, "amount")
            }
        }
        val expectedNet = Money.scale(gross - saleFees - paymentFees)
        val actualNet = payments.fold(Money.ZERO) { acc, payment ->
            val net = CatalogJson.decimal(payment, "transaction_amount") -
                CatalogJson.maps(payment["fee_details"]).fold(Money.ZERO) { inner, fee ->
                    inner + CatalogJson.decimal(fee, "amount")
                }
            acc + net
        }.let { if (it > Money.ZERO) Money.scale(it) else null }
        val canonicalPayments = payments.mapIndexed { index, payment -> toPayment(id, orderedAt, payment, index) }
        return CanonicalSale(
            order = CanonicalOrder(
                externalId = CatalogJson.clipped(id),
                orderedAt = orderedAt,
                buyerName = buyerName(order),
                marketplace = "Mercado Livre",
                items = items.sumOf { CatalogJson.intValue(it, "quantity", 1).coerceAtLeast(1) }.coerceAtLeast(1),
                grossAmount = gross,
                expectedNet = expectedNet,
                actualNet = actualNet,
                status = orderStatus(CatalogJson.text(order, "status")),
            ),
            payments = canonicalPayments,
            fees = fees(id, orderedAt, items, payments),
            refunds = refunds(id, orderedAt, order, payments),
            invoice = invoice(id, gross, orderedAt, fiscal),
        )
    }

    private fun toPayment(orderId: String, fallbackDate: Instant, payment: Map<String, Any?>, index: Int): CanonicalPayment {
        val fees = CatalogJson.maps(payment["fee_details"]).fold(Money.ZERO) { acc, fee ->
            acc + CatalogJson.decimal(fee, "amount")
        }
        val gross = CatalogJson.decimal(payment, "transaction_amount").takeIf { it > Money.ZERO }
            ?: CatalogJson.decimal(payment, "total_paid_amount")
        val paidAt = CatalogJson.instant(payment, "date_approved")
            ?: CatalogJson.instant(payment, "date_created")
            ?: fallbackDate
        val id = CatalogJson.text(payment, "id") ?: "$orderId-pay-$index"
        return CanonicalPayment(
            externalId = CatalogJson.clipped(id),
            paidAt = paidAt,
            gateway = "Mercado Pago",
            method = paymentMethod(CatalogJson.text(payment, "payment_type") ?: CatalogJson.text(payment, "payment_type_id")),
            grossAmount = gross,
            fees = fees,
            netAmount = Money.scale(gross - fees),
            status = paymentStatus(CatalogJson.text(payment, "status")),
        )
    }

    private fun fees(
        orderId: String,
        occurredAt: Instant,
        items: List<Map<String, Any?>>,
        payments: List<Map<String, Any?>>,
    ): List<CanonicalFee> {
        val fromItems = items.mapIndexedNotNull { index, item ->
            val amount = CatalogJson.decimal(item, "sale_fee")
            if (amount <= Money.ZERO) return@mapIndexedNotNull null
            val qty = CatalogJson.intValue(item, "quantity", 1).coerceAtLeast(1)
            val total = Money.scale(amount.multiply(BigDecimal.valueOf(qty.toLong())))
            CanonicalFee(
                externalId = CatalogJson.clipped("$orderId:sale_fee:$index"),
                type = "Comissão marketplace",
                expected = total,
                actual = total,
                source = "Mercado Livre",
                occurredAt = occurredAt,
            )
        }
        val fromPayments = payments.flatMap { payment ->
            val paymentId = CatalogJson.text(payment, "id") ?: orderId
            CatalogJson.maps(payment["fee_details"]).mapIndexed { index, fee ->
                val amount = CatalogJson.decimal(fee, "amount")
                val type = CatalogJson.text(fee, "type").orEmpty()
                CanonicalFee(
                    externalId = CatalogJson.clipped("$paymentId:$type:$index"),
                    type = feeLabel(type),
                    expected = amount,
                    actual = amount,
                    source = "Mercado Pago",
                    occurredAt = CatalogJson.instant(payment, "date_approved") ?: occurredAt,
                )
            }.filter { it.actual > Money.ZERO }
        }
        return fromItems + fromPayments
    }

    private fun refunds(
        orderId: String,
        occurredAt: Instant,
        order: Map<String, Any?>,
        payments: List<Map<String, Any?>>,
    ): List<CanonicalRefund> {
        val fromPayments = payments.mapNotNull { payment ->
            val status = CatalogJson.text(payment, "status").orEmpty()
            val amount = CatalogJson.decimal(payment, "transaction_amount")
            when {
                status == "charged_back" -> CanonicalRefund(
                    externalId = CatalogJson.clipped("${CatalogJson.text(payment, "id")}-chargeback"),
                    type = "chargeback",
                    amount = amount,
                    occurredAt = CatalogJson.instant(payment, "date_last_modified") ?: occurredAt,
                    status = "chargeback",
                )
                status == "refunded" -> CanonicalRefund(
                    externalId = CatalogJson.clipped("${CatalogJson.text(payment, "id")}-refund"),
                    type = "estorno",
                    amount = amount,
                    occurredAt = CatalogJson.instant(payment, "date_last_modified") ?: occurredAt,
                    status = "estornado",
                )
                else -> null
            }
        }
        if (fromPayments.isNotEmpty()) return fromPayments
        if (CatalogJson.text(order, "status") != "cancelled") return emptyList()
        return listOf(
            CanonicalRefund(
                externalId = CatalogJson.clipped("$orderId-cancel"),
                type = "estorno",
                amount = CatalogJson.decimal(order, "total_amount"),
                occurredAt = CatalogJson.instant(order, "date_closed") ?: occurredAt,
                status = "cancelado",
            ),
        )
    }

    private fun invoice(orderId: String, total: BigDecimal, orderedAt: Instant, fiscal: Map<String, Any?>): CanonicalInvoice? {
        if (fiscal.isEmpty()) return null
        val invoice = CatalogJson.child(CatalogJson.child(fiscal, "fiscal_data"), "invoice")
            .ifEmpty { CatalogJson.child(fiscal, "invoice") }
            .ifEmpty { fiscal }
        val key = CatalogJson.text(invoice, "key") ?: CatalogJson.text(invoice, "access_key")
        val documents = CatalogJson.maps(fiscal["fiscal_documents"]).ifEmpty {
            CatalogJson.maps(fiscal["results"])
        }
        val documentId = documents.firstOrNull()?.let { CatalogJson.text(it, "id") }
        if (key.isNullOrBlank() && documentId.isNullOrBlank()) return null
        val number = CatalogJson.text(invoice, "number") ?: documentId ?: orderId
        return CanonicalInvoice(
            externalId = CatalogJson.clipped(documentId ?: "nfe-$orderId"),
            type = "NF-e",
            number = number.take(32),
            accessKey = key,
            issuedAt = CatalogJson.instant(invoice, "date") ?: CatalogJson.instant(invoice, "issued_at") ?: orderedAt,
            total = CatalogJson.decimal(invoice, "amount").takeIf { it > Money.ZERO } ?: total,
            status = if (key.isNullOrBlank()) "pendente" else "autorizada",
        )
    }

    private fun buyerName(order: Map<String, Any?>): String {
        val buyer = CatalogJson.child(order, "buyer")
        return CatalogJson.text(buyer, "nickname")
            ?: listOfNotNull(CatalogJson.text(buyer, "first_name"), CatalogJson.text(buyer, "last_name")).joinToString(" ").ifBlank { null }
            ?: "Comprador ML"
    }

    private fun orderStatus(raw: String?) = when (raw) {
        "cancelled" -> "cancelado"
        "payment_required", "payment_in_process", "pending" -> "pendente"
        else -> "pago"
    }

    private fun paymentStatus(raw: String?) = when (raw) {
        "refunded", "cancelled", "rejected" -> "estornado"
        "charged_back" -> "chargeback"
        "pending", "in_process", "in_mediation" -> "pendente"
        else -> "aprovado"
    }

    private fun paymentMethod(raw: String?) = when {
        raw.isNullOrBlank() -> "Pix"
        raw.contains("credit") || raw.contains("debit") || raw.contains("card") || raw == "visa" || raw == "master" -> "Cartão"
        raw.contains("ticket") || raw.contains("bol") -> "Boleto"
        else -> "Pix"
    }

    private fun feeLabel(type: String) = when (type) {
        "mercadolibre_fee" -> "Comissão marketplace"
        "financing_fee" -> "Tarifa de pagamento"
        "shipping_fee" -> "Frete"
        else -> type.ifBlank { "Tarifa" }
    }
}
