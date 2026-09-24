package br.com.reconcile.integration.ingestion

import br.com.reconcile.integration.ingestion.ml.MercadoLivreSaleMapper
import br.com.reconcile.integration.ingestion.shopee.ShopeeSaleMapper
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketplaceSaleMapperTest {
    private val ml = MercadoLivreSaleMapper()
    private val shopee = ShopeeSaleMapper()

    @Test
    fun `maps mercado livre order payments fees refunds and nfe`() {
        val sale = ml.toSale(
            mapOf(
                "id" to 200001234,
                "date_created" to "2026-08-04T14:22:01.000-04:00",
                "status" to "paid",
                "total_amount" to 100.0,
                "buyer" to mapOf("nickname" to "COMPRADOR"),
                "order_items" to listOf(
                    mapOf("quantity" to 2, "sale_fee" to 5.5),
                ),
                "payments" to listOf(
                    mapOf(
                        "id" to 88,
                        "status" to "approved",
                        "payment_type" to "credit_card",
                        "transaction_amount" to 100.0,
                        "date_approved" to "2026-08-04T14:22:05.000-04:00",
                        "fee_details" to listOf(mapOf("type" to "financing_fee", "amount" to 1.5)),
                    ),
                ),
                "pack_id" to 9,
            ),
            mapOf("fiscal_data" to mapOf("invoice" to mapOf("key" to "NFE123", "number" to 44, "amount" to 100.0))),
        )!!
        assertEquals("200001234", sale.order.externalId)
        assertEquals("COMPRADOR", sale.order.buyerName)
        assertEquals("Mercado Livre", sale.order.marketplace)
        assertEquals("pago", sale.order.status)
        assertEquals(2, sale.order.items)
        assertEquals(BigDecimal("100.00"), sale.order.grossAmount)
        assertEquals(BigDecimal("87.50"), sale.order.expectedNet)
        assertEquals(BigDecimal("87.50"), sale.order.actualNet)
        assertEquals("Cartão", sale.payments.single().method)
        assertEquals("aprovado", sale.payments.single().status)
        assertTrue(sale.fees.any { it.type == "Comissão marketplace" })
        assertTrue(sale.fees.any { it.type == "Tarifa de pagamento" })
        assertEquals("NFE123", sale.invoice?.accessKey)
        assertEquals("autorizada", sale.invoice?.status)
        assertTrue(sale.refunds.isEmpty())
    }

    @Test
    fun `maps cancelled mercado livre payment as chargeback`() {
        val sale = ml.toSale(
            mapOf(
                "id" to "1",
                "date_created" to "2026-08-04T14:22:01Z",
                "status" to "cancelled",
                "total_amount" to 10,
                "buyer" to mapOf("first_name" to "Ana", "last_name" to "Dantas"),
                "order_items" to emptyList<Any>(),
                "payments" to listOf(
                    mapOf("id" to "p1", "status" to "charged_back", "payment_type" to "pix", "transaction_amount" to 10),
                ),
            ),
        )!!
        assertEquals("cancelado", sale.order.status)
        assertEquals("Ana Dantas", sale.order.buyerName)
        assertEquals("chargeback", sale.refunds.single().type)
        assertEquals("Pix", sale.payments.single().method)
    }

    @Test
    fun `maps shopee order detail without touching mercado livre fields`() {
        val sale = shopee.toSale(
            mapOf(
                "order_sn" to "220101ABCDEF",
                "create_time" to 1750000000,
                "pay_time" to 1750000100,
                "order_status" to "COMPLETED",
                "buyer_username" to "loja_teste",
                "total_amount" to 80.4,
                "escrow_amount" to 70.1,
                "commission_fee" to 10.3,
                "payment_method" to "Shopee Pay",
                "item_list" to listOf(mapOf("model_quantity_purchased" to 3)),
            ),
        )!!
        assertEquals("220101ABCDEF", sale.order.externalId)
        assertEquals("Shopee", sale.order.marketplace)
        assertEquals("loja_teste", sale.order.buyerName)
        assertEquals(3, sale.order.items)
        assertEquals("Pix", sale.payments.single().method)
        assertEquals("Shopee", sale.payments.single().gateway)
        assertEquals(BigDecimal("70.10"), sale.order.expectedNet)
        assertEquals(BigDecimal("70.10"), sale.order.actualNet)

        val fromEscrow = shopee.toSale(
            mapOf(
                "order_sn" to "220101ABCDEF",
                "create_time" to 1750000000,
                "order_status" to "COMPLETED",
                "total_amount" to 80.4,
                "escrow_detail" to mapOf("escrow_amount" to 70.1, "commission_fee" to 10.3),
            ),
        )!!
        assertEquals(BigDecimal("70.10"), fromEscrow.order.actualNet)
        assertEquals(BigDecimal("10.30"), fromEscrow.fees.single().actual)
        assertNull(sale.invoice)
        assertTrue(sale.refunds.isEmpty())
    }

    @Test
    fun `ignores orders without marketplace identity`() {
        assertNull(ml.toSale(mapOf("status" to "paid")))
        assertNull(shopee.toSale(mapOf("order_status" to "UNPAID")))
    }
}
