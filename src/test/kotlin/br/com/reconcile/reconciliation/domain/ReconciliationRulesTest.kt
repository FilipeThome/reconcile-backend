package br.com.reconcile.reconciliation.domain

import br.com.reconcile.reconciliation.domain.rules.BelowExpectedRule
import br.com.reconcile.reconciliation.domain.rules.InvoiceAmountMismatchRule
import br.com.reconcile.reconciliation.domain.rules.MissingInvoiceRule
import br.com.reconcile.reconciliation.domain.rules.PaidWithoutPayoutRule
import br.com.reconcile.reconciliation.domain.rules.RuleContext
import br.com.reconcile.reconciliation.domain.rules.UnreflectedRefundRule
import br.com.reconcile.shared.domain.Money
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReconciliationRulesTest {
    private val paymentAt = Instant.parse("2026-05-04T11:24:00Z")

    private fun ctx(
        payoutPresent: Boolean = false,
        now: Instant = Instant.parse("2026-05-20T00:00:00Z"),
        actualNet: BigDecimal? = null,
        invoiceAuthorized: Boolean = true,
        invoiceTotal: BigDecimal? = Money.of("1000"),
        orderGross: BigDecimal = Money.of("1000"),
        refund: Boolean = false,
        refundReflected: Boolean = true,
    ) = RuleContext(
        paymentApproved = true,
        payoutPresent = payoutPresent,
        paymentAt = paymentAt,
        now = now,
        deadlineDays = 14,
        expectedNet = Money.of("842.31"),
        actualNet = actualNet,
        tolerance = Money.of("0.05"),
        invoiceRequired = true,
        invoiceAuthorized = invoiceAuthorized,
        invoiceAt = paymentAt,
        invoiceDeadlineHours = 48,
        invoiceTotal = invoiceTotal,
        orderGross = orderGross,
        refundOrChargeback = refund,
        refundReflected = refundReflected,
    )

    @Test
    fun `rule 1 stays pending before deadline and opens after`() {
        val pending = PaidWithoutPayoutRule().evaluate(ctx(now = Instant.parse("2026-05-10T00:00:00Z")))
        assertNotNull(pending)
        assertTrue(pending.pendingSettlement)

        val open = PaidWithoutPayoutRule().evaluate(ctx(now = Instant.parse("2026-05-20T00:00:00Z")))
        assertNotNull(open)
        assertEquals(false, open.pendingSettlement)
        assertEquals(Money.of("842.31"), open.amount)
    }

    @Test
    fun `rule 2 detects received below expected`() {
        val finding = BelowExpectedRule().evaluate(ctx(actualNet = Money.of("812.31")))
        assertEquals(Money.of("30.00"), finding?.amount)
    }

    @Test
    fun `rule 3 flags missing invoice after deadline`() {
        val finding = MissingInvoiceRule().evaluate(ctx(invoiceAuthorized = false, invoiceTotal = null))
        assertEquals("nota_ausente", finding?.cause)
    }

    @Test
    fun `rule 4 flags invoice total mismatch`() {
        val finding = InvoiceAmountMismatchRule().evaluate(
            ctx(invoiceTotal = Money.of("219.90"), orderGross = Money.of("210.00")),
        )
        assertEquals(Money.of("9.90"), finding?.amount)
    }

    @Test
    fun `rule 5 flags unreflected chargeback`() {
        val finding = UnreflectedRefundRule().evaluate(ctx(refund = true, refundReflected = false, actualNet = Money.of("189.90")))
        assertEquals("chargeback_sem_reflexo", finding?.cause)
    }
}
