package br.com.reconcile.reconciliation.domain.rules

import br.com.reconcile.shared.domain.Money
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class RuleContext(
    val paymentApproved: Boolean,
    val payoutPresent: Boolean,
    val paymentAt: Instant?,
    val now: Instant,
    val deadlineDays: Int,
    val expectedNet: BigDecimal,
    val actualNet: BigDecimal?,
    val tolerance: BigDecimal,
    val invoiceRequired: Boolean,
    val invoiceAuthorized: Boolean,
    val invoiceAt: Instant?,
    val invoiceDeadlineHours: Int,
    val invoiceTotal: BigDecimal?,
    val orderGross: BigDecimal,
    val refundOrChargeback: Boolean,
    val refundReflected: Boolean,
)

data class RuleFinding(
    val code: String,
    val cause: String,
    val description: String,
    val amount: BigDecimal,
    val pendingSettlement: Boolean = false,
)

fun interface ReconciliationRuleStrategy {
    fun evaluate(ctx: RuleContext): RuleFinding?
}

class PaidWithoutPayoutRule : ReconciliationRuleStrategy {
    override fun evaluate(ctx: RuleContext): RuleFinding? {
        if (!ctx.paymentApproved || ctx.payoutPresent || ctx.paymentAt == null) return null
        val elapsed = Duration.between(ctx.paymentAt, ctx.now)
        val deadline = Duration.ofDays(ctx.deadlineDays.toLong())
        return if (elapsed <= deadline) {
            RuleFinding(
                code = "r1",
                cause = "repasse_nao_realizado",
                description = "Pagamento aprovado aguardando payout dentro da janela de liquidação.",
                amount = ctx.expectedNet,
                pendingSettlement = true,
            )
        } else {
            RuleFinding(
                code = "r1",
                cause = "repasse_nao_realizado",
                description = "Pedido pago sem payout após o settlement deadline.",
                amount = ctx.expectedNet,
            )
        }
    }
}

class BelowExpectedRule : ReconciliationRuleStrategy {
    override fun evaluate(ctx: RuleContext): RuleFinding? {
        val actual = ctx.actualNet ?: return null
        val gap = Money.scale(ctx.expectedNet.subtract(actual))
        if (gap <= ctx.tolerance) return null
        return RuleFinding(
            code = "r2",
            cause = "tarifa_inesperada",
            description = "Valor recebido abaixo do expected settlement.",
            amount = gap,
        )
    }
}

class MissingInvoiceRule : ReconciliationRuleStrategy {
    override fun evaluate(ctx: RuleContext): RuleFinding? {
        if (!ctx.invoiceRequired || ctx.invoiceAuthorized || ctx.paymentAt == null) return null
        val elapsed = Duration.between(ctx.paymentAt, ctx.now)
        if (elapsed < Duration.ofHours(ctx.invoiceDeadlineHours.toLong())) return null
        return RuleFinding(
            code = "r3",
            cause = "nota_ausente",
            description = "Documento fiscal obrigatório ausente após o prazo configurado.",
            amount = ctx.orderGross,
        )
    }
}

class InvoiceAmountMismatchRule : ReconciliationRuleStrategy {
    override fun evaluate(ctx: RuleContext): RuleFinding? {
        val invoice = ctx.invoiceTotal ?: return null
        val gap = Money.scale(invoice.subtract(ctx.orderGross).abs())
        if (gap <= ctx.tolerance) return null
        return RuleFinding(
            code = "r4",
            cause = "valor_faturado_divergente",
            description = "invoice_total diverge de gross_amount além da tolerância.",
            amount = gap,
        )
    }
}

class UnreflectedRefundRule : ReconciliationRuleStrategy {
    override fun evaluate(ctx: RuleContext): RuleFinding? {
        if (!ctx.refundOrChargeback || ctx.refundReflected) return null
        return RuleFinding(
            code = "r5",
            cause = "chargeback_sem_reflexo",
            description = "Estorno ou chargeback sem reflexo no settlement/fechamento.",
            amount = ctx.actualNet ?: ctx.expectedNet,
        )
    }
}
