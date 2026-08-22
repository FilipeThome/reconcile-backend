package br.com.reconcile.reconciliation.application

import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.ledger.infra.FeeRepository
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.ledger.infra.PaymentRepository
import br.com.reconcile.ledger.infra.PayoutOrderRepository
import br.com.reconcile.ledger.infra.RefundRepository
import br.com.reconcile.ledger.infra.SettlementRepository
import br.com.reconcile.reconciliation.domain.CalculationComponent
import br.com.reconcile.reconciliation.domain.CalculationInput
import br.com.reconcile.reconciliation.domain.CaseStateMachine
import br.com.reconcile.reconciliation.domain.CaseStatus
import br.com.reconcile.reconciliation.domain.MatchingEngine
import br.com.reconcile.reconciliation.domain.MatchingLayer
import br.com.reconcile.reconciliation.domain.SettlementCalculationEngine
import br.com.reconcile.reconciliation.domain.rules.BelowExpectedRule
import br.com.reconcile.reconciliation.domain.rules.InvoiceAmountMismatchRule
import br.com.reconcile.reconciliation.domain.rules.MissingInvoiceRule
import br.com.reconcile.reconciliation.domain.rules.PaidWithoutPayoutRule
import br.com.reconcile.reconciliation.domain.rules.ReconciliationRuleStrategy
import br.com.reconcile.reconciliation.domain.rules.RuleContext
import br.com.reconcile.reconciliation.domain.rules.UnreflectedRefundRule
import br.com.reconcile.reconciliation.infra.CaseCommentEntity
import br.com.reconcile.reconciliation.infra.CaseCommentRepository
import br.com.reconcile.reconciliation.infra.CaseEntity
import br.com.reconcile.reconciliation.infra.CaseRepository
import br.com.reconcile.reconciliation.infra.CaseTimelineEntity
import br.com.reconcile.reconciliation.infra.CaseTimelineRepository
import br.com.reconcile.reconciliation.infra.RuleRepository
import br.com.reconcile.reconciliation.infra.SnapshotRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.domain.Money
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

@Service
class CaseService(
    private val cases: CaseRepository,
    private val comments: CaseCommentRepository,
    private val timeline: CaseTimelineRepository,
    private val snapshots: SnapshotRepository,
    private val orders: OrderRepository,
    private val audit: AuditService,
) {
    fun list(status: String?): List<Map<String, Any?>> {
        val tenant = currentUser().tenantId
        return cases.findByTenantIdOrderByOpenedAtDesc(tenant)
            .filter { status.isNullOrBlank() || status == "todas" || it.status == status }
            .map { caseSummary(it) }
    }

    fun get(externalId: String): Map<String, Any?> {
        val item = requireCase(externalId)
        val order = orders.findByTenantIdAndExternalId(item.tenantId, item.orderExternalId)
        val snapshot = order?.let { snapshots.findByTenantIdAndOrderId(item.tenantId, it.id) }
        return caseSummary(item) + mapOf(
            "description" to item.description,
            "suggestedAction" to item.suggestedAction,
            "confidence" to item.confidence,
            "owner" to item.ownerName,
            "comments" to comments.findByCaseIdOrderByCreatedAtAsc(item.id).map {
                mapOf("id" to it.id, "author" to it.author, "at" to it.createdAt.toString(), "text" to it.body)
            },
            "timeline" to timeline.findByCaseIdOrderByOccurredAtAsc(item.id).map {
                mapOf("id" to it.id, "at" to it.occurredAt.toString(), "title" to it.title, "detail" to it.detail, "tone" to it.tone)
            },
            "calculation" to snapshot?.let {
                mapOf(
                    "id" to it.externalId,
                    "version" to it.engineVersion,
                    "gross" to it.grossAmount,
                    "expected" to it.expectedAmount,
                    "actual" to it.actualAmount,
                    "difference" to it.differenceAmount,
                    "components" to it.components,
                )
            },
        )
    }

    @Transactional
    fun updateStatus(externalId: String, status: String) {
        val item = requireCase(externalId)
        val next = CaseStateMachine.transition(CaseStatus.valueOf(item.status), CaseStatus.valueOf(status))
        item.status = next.name
        cases.save(item)
        timeline.save(
            CaseTimelineEntity(
                tenantId = item.tenantId,
                caseId = item.id,
                title = "Status alterado",
                detail = "Novo status: ${next.name}",
                tone = "neutral",
            ),
        )
        val user = currentUser()
        audit.record(user.tenantId, user.displayName, "case.status", "case", item.externalId, """{"status":"${next.name}"}""")
    }

    @Transactional
    fun addComment(externalId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty() || body.length > 2000) throw UnprocessableException("Comentário inválido")
        val item = requireCase(externalId)
        val user = currentUser()
        comments.save(CaseCommentEntity(tenantId = item.tenantId, caseId = item.id, author = user.displayName, body = body))
        audit.record(user.tenantId, user.displayName, "case.comment", "case", item.externalId)
    }

    private fun requireCase(externalId: String): CaseEntity =
        cases.findByTenantIdAndExternalId(currentUser().tenantId, externalId)
            ?: throw NotFoundException("Caso $externalId não encontrado")

    private fun caseSummary(item: CaseEntity) = mapOf(
        "id" to item.externalId,
        "openedAt" to item.openedAt.toString(),
        "orderId" to item.orderExternalId,
        "paymentId" to item.paymentExternalId,
        "settlementId" to item.settlementExternalId,
        "payoutId" to item.payoutExternalId,
        "invoiceId" to item.invoiceExternalId,
        "cause" to item.cause,
        "amount" to item.amount,
        "recoverable" to item.recoverableAmount,
        "confirmedLoss" to item.confirmedLossAmount,
        "impact" to item.impact,
        "status" to item.status,
        "priority" to item.priority,
        "owner" to item.ownerName,
    )
}

@Service
class RuleQueryService(private val rules: RuleRepository, private val audit: AuditService) {
    fun list() = rules.findByTenantId(currentUser().tenantId).map {
        mapOf("id" to it.externalId, "name" to it.name, "description" to it.description, "type" to it.ruleType, "active" to it.active)
    }

    @Transactional
    fun toggle(externalId: String) {
        val user = currentUser()
        val rule = rules.findByTenantIdAndExternalId(user.tenantId, externalId)
            ?: throw NotFoundException("Regra $externalId não encontrada")
        rule.active = !rule.active
        rules.save(rule)
        audit.record(user.tenantId, user.displayName, "rule.toggle", "rule", externalId)
    }
}

@Service
class EngineService(
    private val tenants: TenantRepository,
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val settlements: SettlementRepository,
    private val payoutOrders: PayoutOrderRepository,
    private val invoices: InvoiceRepository,
    private val refunds: RefundRepository,
    private val fees: FeeRepository,
    private val cases: CaseRepository,
    private val rules: RuleRepository,
    private val timeline: CaseTimelineRepository,
    private val audit: AuditService,
) {
    private val engine = SettlementCalculationEngine()
    private val matching = MatchingEngine()
    private val strategies: Map<String, ReconciliationRuleStrategy> = mapOf(
        "r1" to PaidWithoutPayoutRule(),
        "r2" to BelowExpectedRule(),
        "r3" to MissingInvoiceRule(),
        "r4" to InvoiceAmountMismatchRule(),
        "r5" to UnreflectedRefundRule(),
    )

    fun overview(): Map<String, Any> = mapOf(
        "version" to "sce-v1.4",
        "calculations" to orders.findByTenantIdOrderByOrderedAtDesc(currentUser().tenantId).size,
        "layers" to MatchingLayer.entries.map { it.name },
        "components" to listOf(
            mapOf("label" to "Comissão marketplace", "amount" to 98420.30, "pct" to 50.6),
            mapOf("label" to "Tarifa de pagamento", "amount" to 32110.80, "pct" to 16.5),
            mapOf("label" to "Frete", "amount" to 28440.20, "pct" to 14.6),
            mapOf("label" to "Retenção tributária", "amount" to 18220.00, "pct" to 9.4),
            mapOf("label" to "Publicidade", "amount" to 11840.40, "pct" to 6.1),
            mapOf("label" to "Ajustes e campanhas", "amount" to 5410.10, "pct" to 2.8),
        ),
    )

    @Transactional
    fun reprocess(orderExternalId: String): Map<String, Any?> {
        val user = currentUser()
        val tenant = tenants.findById(user.tenantId).orElseThrow()
        val order = orders.findByTenantIdAndExternalId(user.tenantId, orderExternalId)
            ?: throw NotFoundException("Pedido $orderExternalId não encontrado")
        val payment = payments.findByTenantIdAndOrderId(user.tenantId, order.id).firstOrNull()
        val settlement = settlements.findByTenantIdAndOrderId(user.tenantId, order.id)
        val payoutPresent = payoutOrders.findByOrderId(order.id).isNotEmpty()
        val invoice = invoices.findByTenantIdAndOrderId(user.tenantId, order.id)
        val orderRefunds = refunds.findByTenantIdAndOrderId(user.tenantId, order.id)
        val orderFees = fees.findByTenantIdAndOrderId(user.tenantId, order.id)

        val components = if (orderFees.isNotEmpty()) {
            orderFees.map { CalculationComponent(it.feeType, it.actualAmount.negate()) }
        } else {
            listOf(
                CalculationComponent("Comissão marketplace", Money.of("-120")),
                CalculationComponent("Frete", Money.of("-22.90")),
                CalculationComponent("Tarifa de pagamento", Money.of("-14.79")),
            )
        }
        val result = engine.calculate(
            CalculationInput(gross = order.grossAmount, components = components, actual = order.actualNet ?: Money.ZERO),
        )
        matching.matchExact(MatchingLayer.ORDER_PAYMENT, order.externalId, payment?.let { order.externalId })
        matching.matchExact(MatchingLayer.PAYMENT_SETTLEMENT, payment?.externalId, settlement?.let { payment?.externalId })

        val ctx = RuleContext(
            paymentApproved = payment?.status == "aprovado",
            payoutPresent = payoutPresent,
            paymentAt = payment?.paidAt,
            now = Instant.parse("2026-06-03T12:00:00Z"),
            deadlineDays = tenant.settlementDeadlineDays,
            expectedNet = result.expected,
            actualNet = order.actualNet,
            tolerance = tenant.amountTolerance,
            invoiceRequired = tenant.invoiceRequired,
            invoiceAuthorized = invoice?.status == "autorizada",
            invoiceAt = invoice?.issuedAt,
            invoiceDeadlineHours = tenant.invoiceDeadlineHours,
            invoiceTotal = invoice?.totalAmount,
            orderGross = order.grossAmount,
            refundOrChargeback = orderRefunds.isNotEmpty(),
            refundReflected = orderRefunds.all { it.reflectedInSettlement },
        )
        val active = rules.findByTenantId(user.tenantId).filter { it.active }.map { it.externalId }.toSet()
        val findings = strategies.filterKeys { it in active }.values.mapNotNull { it.evaluate(ctx) }

        findings.firstOrNull { !it.pendingSettlement }?.let { finding ->
            val existing = cases.findByTenantIdAndOrderExternalId(user.tenantId, order.externalId)
            if (existing == null) {
                val created = cases.save(
                    CaseEntity(
                        tenantId = user.tenantId,
                        externalId = "DIV-REPROCESS-${order.externalId.takeLast(6)}",
                        orderExternalId = order.externalId,
                        paymentExternalId = payment?.externalId,
                        settlementExternalId = settlement?.externalId,
                        invoiceExternalId = invoice?.externalId,
                        cause = finding.cause,
                        description = finding.description,
                        amount = finding.amount,
                        recoverableAmount = finding.amount,
                        confirmedLossAmount = Money.ZERO,
                        impact = "recuperavel",
                        status = "aberta",
                        priority = "media",
                        ownerName = user.displayName,
                        confidence = BigDecimal("0.9000"),
                        suggestedAction = "Revisar evidências geradas pelo reprocessamento.",
                    ),
                )
                timeline.save(
                    CaseTimelineEntity(
                        tenantId = user.tenantId,
                        caseId = created.id,
                        title = "Caso aberto por reprocessamento",
                        detail = finding.description,
                        tone = "danger",
                    ),
                )
            }
        }

        audit.record(user.tenantId, user.displayName, "case.reprocess", "order", order.externalId)
        return mapOf(
            "orderId" to order.externalId,
            "expected" to result.expected,
            "actual" to result.actual,
            "difference" to result.difference,
            "findings" to findings.map { mapOf("code" to it.code, "cause" to it.cause, "pendingSettlement" to it.pendingSettlement) },
        )
    }
}

@Service
class DashboardService(
    private val cases: CaseRepository,
    private val closings: br.com.reconcile.closing.infra.ClosingRepository,
) {
    fun dashboard(): Map<String, Any> {
        val tenant = currentUser().tenantId
        val closing = closings.findByTenantIdOrderByPeriodDesc(tenant).firstOrNull()
        val openCases = cases.findByTenantIdOrderByOpenedAtDesc(tenant)
            .filter { it.status == "aberta" || it.status == "em_analise" }
        return mapOf(
            "kpis" to mapOf(
                "gross" to (closing?.grossAmount ?: BigDecimal.ZERO),
                "expected" to (closing?.expectedAmount ?: BigDecimal.ZERO),
                "received" to (closing?.receivedAmount ?: BigDecimal.ZERO),
                "divergence" to (closing?.openDiffAmount ?: BigDecimal.ZERO),
                "pendingInvoices" to (closing?.pendingInvoices ?: 0),
                "orders" to (closing?.ordersCount ?: 0),
            ),
            "dailySeries" to listOf(
                mapOf("day" to "01", "esperado" to 31200, "recebido" to 30110, "divergencia" to 1090),
                mapOf("day" to "13", "esperado" to 40240, "recebido" to 38110, "divergencia" to 2130),
                mapOf("day" to "21", "esperado" to 42180, "recebido" to 39850, "divergencia" to 2330),
                mapOf("day" to "31", "esperado" to 35680, "recebido" to 35100, "divergencia" to 580),
            ),
            "causes" to listOf(
                mapOf("cause" to "Tarifa não identificada", "value" to 9840, "pct" to 39.6),
                mapOf("cause" to "Repasse não realizado", "value" to 6410, "pct" to 25.8),
                mapOf("cause" to "Estorno / chargeback", "value" to 3720, "pct" to 15.0),
                mapOf("cause" to "Nota ausente ou atrasada", "value" to 2880, "pct" to 11.6),
                mapOf("cause" to "Valor faturado divergente", "value" to 1990, "pct" to 8.0),
            ),
            "periodLabel" to (closing?.label ?: "Período atual"),
            "recoverable" to cases.findByTenantIdOrderByOpenedAtDesc(tenant).sumOf { it.recoverableAmount },
            "confirmedLoss" to cases.findByTenantIdOrderByOpenedAtDesc(tenant).sumOf { it.confirmedLossAmount },
            "actionQueue" to openCases.take(8).map {
                mapOf(
                    "id" to it.externalId,
                    "cause" to it.cause,
                    "description" to it.description,
                    "amount" to it.amount,
                    "status" to it.status,
                    "priority" to it.priority,
                )
            },
        )
    }
}
