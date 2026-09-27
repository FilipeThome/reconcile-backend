package br.com.reconcile.reconciliation.application

import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.ledger.infra.FeeRepository
import br.com.reconcile.ledger.infra.OrderEntity
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.ledger.infra.PaymentRepository
import br.com.reconcile.ledger.infra.PayoutOrderRepository
import br.com.reconcile.ledger.infra.PayoutRepository
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
import br.com.reconcile.reconciliation.infra.SnapshotEntity
import br.com.reconcile.reconciliation.infra.SnapshotRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.apiMessage
import br.com.reconcile.shared.api.PeriodFilter
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.domain.Money
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

@Service
class CaseService(
    private val cases: CaseRepository,
    private val comments: CaseCommentRepository,
    private val timeline: CaseTimelineRepository,
    private val snapshots: SnapshotRepository,
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val audit: AuditService,
) {
    fun list(status: String?, filter: PeriodFilter): List<Map<String, Any?>> {
        val tenant = currentUser().tenantId
        val orderMap = orders.findByTenantIdOrderByOrderedAtDesc(tenant).associateBy { it.externalId }
        val paymentsByOrder = payments.findByTenantIdOrderByPaidAtDesc(tenant).groupBy { it.orderId }
        return cases.findByTenantIdOrderByOpenedAtDesc(tenant)
            .filter { status.isNullOrBlank() || status == "todas" || it.status == status }
            .filter { item ->
                val order = orderMap[item.orderExternalId]
                val gateway = order?.let { paymentsByOrder[it.id]?.firstOrNull()?.gateway }
                filter.matchesInstant(order?.orderedAt ?: item.openedAt) &&
                    filter.matchesMarketplace(order?.marketplace) &&
                    filter.matchesGateway(gateway)
            }
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
        val current = parseCaseStatus(item.status)
        val requested = parseCaseStatus(status)
        val next = CaseStateMachine.transition(current, requested)
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
        if (body.isEmpty() || body.length > 2000) throw UnprocessableException(apiMessage("comment.invalid"))
        val item = requireCase(externalId)
        val user = currentUser()
        comments.save(CaseCommentEntity(tenantId = item.tenantId, caseId = item.id, author = user.displayName, body = body))
        audit.record(user.tenantId, user.displayName, "case.comment", "case", item.externalId)
    }

    private fun parseCaseStatus(status: String) =
        runCatching { CaseStatus.valueOf(status) }.getOrElse {
            throw UnprocessableException(apiMessage("case.status", status))
        }

    private fun requireCase(externalId: String): CaseEntity =
        cases.findByTenantIdAndExternalId(currentUser().tenantId, externalId)
            ?: throw NotFoundException(apiMessage("case.missing", externalId))

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
        "description" to item.description,
        "suggestedAction" to item.suggestedAction,
        "confidence" to item.confidence,
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
            ?: throw NotFoundException(apiMessage("rule.missing", externalId))
        rule.active = !rule.active
        rules.save(rule)
        audit.record(user.tenantId, user.displayName, "rule.toggle", "rule", externalId)
    }
}

@Service
class SnapshotQueryService(
    private val snapshots: SnapshotRepository,
    private val orders: OrderRepository,
) {
    fun list(): List<Map<String, Any?>> {
        val tenant = currentUser().tenantId
        val orderMap = orders.findByTenantIdOrderByOrderedAtDesc(tenant).associateBy { it.id }
        return snapshots.findByTenantId(tenant).map { snapshotDto(it, orderMap[it.orderId]?.externalId) }
    }

    fun get(externalId: String): Map<String, Any?> {
        val tenant = currentUser().tenantId
        val item = snapshots.findByTenantIdAndExternalId(tenant, externalId)
            ?: throw NotFoundException(apiMessage("snapshot.missing", externalId))
        val order = orders.findById(item.orderId).orElse(null)
        return snapshotDto(item, order?.externalId)
    }

    private fun snapshotDto(item: SnapshotEntity, orderId: String?) = mapOf(
        "id" to item.externalId,
        "orderId" to (orderId ?: ""),
        "version" to item.engineVersion,
        "gross" to item.grossAmount,
        "expected" to item.expectedAmount,
        "actual" to item.actualAmount,
        "difference" to item.differenceAmount,
        "components" to item.components,
    )
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
    private val snapshots: SnapshotRepository,
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

    fun overview(): Map<String, Any?> {
        val tenantId = currentUser().tenantId
        val tenant = tenants.findById(tenantId).orElseThrow()
        val snapshotRows = snapshots.findByTenantId(tenantId)
        val feeRows = fees.findByTenantId(tenantId)
        val grouped = feeRows.groupBy { it.feeType }.mapValues { (_, rows) ->
            rows.fold(BigDecimal.ZERO) { acc, item -> acc + item.actualAmount }
        }.entries.sortedByDescending { it.value }
        val total = grouped.fold(BigDecimal.ZERO) { acc, item -> acc + item.value }
        val example = snapshotRows.firstOrNull { it.externalId == "SNAP-001" } ?: snapshotRows.firstOrNull()
        val exampleOrder = example?.let { orders.findById(it.orderId).orElse(null) }
        return mapOf(
            "version" to "sce-v1.4",
            "calculations" to snapshotRows.size,
            "layers" to MatchingLayer.entries.map { it.name },
            "components" to grouped.map { (label, amount) ->
                mapOf(
                    "label" to label,
                    "amount" to amount,
                    "pct" to if (total.compareTo(BigDecimal.ZERO) == 0) 0.0
                    else amount.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toDouble(),
                )
            },
            "example" to example?.let {
                mapOf(
                    "id" to it.externalId,
                    "orderId" to (exampleOrder?.externalId ?: ""),
                    "version" to it.engineVersion,
                    "gross" to it.grossAmount,
                    "expected" to it.expectedAmount,
                    "actual" to it.actualAmount,
                    "difference" to it.differenceAmount,
                    "components" to it.components,
                )
            },
            "tolerances" to mapOf(
                "cents" to tenant.amountTolerance,
                "dateDays" to tenant.dateToleranceDays,
                "settlementDeadlineDays" to tenant.settlementDeadlineDays,
            ),
        )
    }

    @Transactional
    fun reprocess(orderExternalId: String): Map<String, Any?> {
        val user = currentUser()
        val tenant = tenants.findById(user.tenantId).orElseThrow()
        val order = orders.findByTenantIdAndExternalId(user.tenantId, orderExternalId)
            ?: throw NotFoundException(apiMessage("order.missing", orderExternalId))
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
    private val orders: OrderRepository,
    private val payments: PaymentRepository,
    private val invoices: InvoiceRepository,
    private val payouts: PayoutRepository,
) {
    fun dashboard(rawFilter: PeriodFilter): Map<String, Any> {
        val tenant = currentUser().tenantId
        val closing = closings.findByTenantIdOrderByPeriodDesc(tenant).firstOrNull()
        val filter = rawFilter.defaultToPeriod(closing?.period)
        val allOrders = orders.findByTenantIdOrderByOrderedAtDesc(tenant)
        val paymentsByOrder = payments.findByTenantIdOrderByPaidAtDesc(tenant).groupBy { it.orderId }
        val invoicesByOrder = invoices.findByTenantId(tenant).associateBy { it.orderId }
        val scoped = allOrders.filter { order -> matches(order, paymentsByOrder[order.id]?.firstOrNull()?.gateway, filter) }
        val previous = allOrders.filter { order ->
            matches(order, paymentsByOrder[order.id]?.firstOrNull()?.gateway, filter.previous())
        }
        val orderIds = scoped.map { it.externalId }.toSet()
        val previousIds = previous.map { it.externalId }.toSet()
        val allCases = cases.findByTenantIdOrderByOpenedAtDesc(tenant)
        val scopedCases = allCases.filter { it.orderExternalId in orderIds }
        val priorCases = allCases.filter { it.orderExternalId in previousIds }
        val current = totals(scoped, scopedCases, invoicesByOrder)
        val prior = totals(previous, priorCases, invoicesByOrder)
        val openCases = scopedCases.filter { it.status == "aberta" || it.status == "em_analise" }
        val upcoming = payouts.findByTenantIdOrderByPaidAtDesc(tenant)
            .filter { it.status == "previsto" && filter.matchesGateway(it.gateway) }
            .take(5)
        return mapOf(
            "kpis" to mapOf(
                "gross" to current.gross,
                "expected" to current.expected,
                "received" to current.received,
                "divergence" to current.divergence,
                "confirmedLoss" to current.loss,
                "recoverable" to current.recoverable,
                "pendingSettlement" to current.pendingSettlement,
                "pendingInvoices" to current.pendingInvoices,
                "orders" to scoped.size,
                "avgTicket" to current.avgTicket,
                "itemsSold" to current.items,
                "reconciledPct" to current.reconciledPct,
                "divergentPct" to current.divergentPct,
                "pendingPct" to current.pendingPct,
                "vsGross" to delta(current.gross, prior.gross),
                "vsExpected" to delta(current.expected, prior.expected),
                "vsReceived" to delta(current.received, prior.received),
                "vsDivergence" to delta(current.divergence, prior.divergence),
                "vsLoss" to delta(current.loss, prior.loss),
            ),
            "dailySeries" to dailySeries(scoped),
            "causes" to causeBreakdown(scopedCases),
            "periodLabel" to filter.label(),
            "recoverable" to current.recoverable,
            "confirmedLoss" to current.loss,
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
            "upcomingPayouts" to upcoming.map {
                mapOf(
                    "id" to it.externalId,
                    "netAmount" to it.netAmount,
                    "status" to it.status,
                    "date" to it.paidAt.toString(),
                    "gateway" to it.gateway,
                )
            },
        )
    }

    private fun matches(order: OrderEntity, gateway: String?, filter: PeriodFilter) =
        filter.matchesInstant(order.orderedAt) &&
            filter.matchesMarketplace(order.marketplace) &&
            filter.matchesGateway(gateway)

    private fun totals(
        scoped: List<OrderEntity>,
        scopedCases: List<CaseEntity>,
        invoicesByOrder: Map<java.util.UUID, br.com.reconcile.fiscal.infra.InvoiceEntity>,
    ): DashTotals {
        val gross = scoped.fold(BigDecimal.ZERO) { acc, item -> acc + item.grossAmount }
        val expected = scoped.fold(BigDecimal.ZERO) { acc, item -> acc + item.expectedNet }
        val received = scoped.fold(BigDecimal.ZERO) { acc, item -> acc + (item.actualNet ?: BigDecimal.ZERO) }
        val pendingSettlement = scoped
            .filter { it.reconciliationStatus == "pendente_liquidacao" || it.actualNet == null }
            .fold(BigDecimal.ZERO) { acc, item -> acc + item.expectedNet }
        val count = scoped.size.coerceAtLeast(1)
        val reconciled = scoped.count { it.reconciliationStatus.startsWith("reconciliado") }
        val divergent = scoped.count { it.reconciliationStatus == "divergente" || it.reconciliationStatus == "revisao_manual" }
        val pending = scoped.size - reconciled - divergent
        return DashTotals(
            gross = gross,
            expected = expected,
            received = received,
            divergence = expected - received,
            loss = scopedCases.fold(BigDecimal.ZERO) { acc, item -> acc + item.confirmedLossAmount },
            recoverable = scopedCases.fold(BigDecimal.ZERO) { acc, item -> acc + item.recoverableAmount },
            pendingSettlement = pendingSettlement,
            pendingInvoices = scoped.count { invoicesByOrder[it.id] == null },
            avgTicket = if (scoped.isEmpty()) BigDecimal.ZERO else gross.divide(BigDecimal(scoped.size), 2, RoundingMode.HALF_UP),
            items = scoped.sumOf { it.items },
            reconciledPct = pct(reconciled, count),
            divergentPct = pct(divergent, count),
            pendingPct = pct(pending.coerceAtLeast(0), count),
        )
    }

    private fun dailySeries(scoped: List<OrderEntity>): List<Map<String, Any>> =
        scoped.groupBy { it.orderedAt.atZone(ZoneOffset.UTC).toLocalDate() }
            .toSortedMap()
            .map { (day, rows) ->
                val expected = rows.fold(BigDecimal.ZERO) { acc, item -> acc + item.expectedNet }
                val received = rows.fold(BigDecimal.ZERO) { acc, item -> acc + (item.actualNet ?: BigDecimal.ZERO) }
                mapOf(
                    "day" to day.dayOfMonth.toString().padStart(2, '0'),
                    "esperado" to expected,
                    "recebido" to received,
                    "divergencia" to expected - received,
                )
            }

    private fun causeBreakdown(scopedCases: List<CaseEntity>): List<Map<String, Any>> {
        val grouped = scopedCases.groupBy { causeLabel(it.cause) }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount } }
            .entries
            .sortedByDescending { it.value }
        val total = grouped.fold(BigDecimal.ZERO) { acc, item -> acc + item.value }
        return grouped.map { (cause, value) ->
            mapOf(
                "cause" to cause,
                "value" to value,
                "pct" to if (total.compareTo(BigDecimal.ZERO) == 0) 0.0 else
                    value.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toDouble(),
            )
        }
    }

    private fun causeLabel(cause: String) = when (cause) {
        "repasse_nao_realizado" -> "Repasse não realizado"
        "tarifa_inesperada" -> "Tarifa inesperada"
        "nota_ausente" -> "Nota ausente ou atrasada"
        "valor_faturado_divergente" -> "Valor faturado divergente"
        "estorno_parcial", "chargeback_sem_reflexo" -> "Estorno / chargeback"
        "duplicidade" -> "Duplicidade"
        else -> cause
    }

    private fun pct(part: Int, total: Int) =
        if (total == 0) 0.0 else BigDecimal(part * 100.0 / total).setScale(1, RoundingMode.HALF_UP).toDouble()

    private fun delta(current: BigDecimal, previous: BigDecimal) =
        if (previous.compareTo(BigDecimal.ZERO) == 0) 0.0
        else current.subtract(previous).multiply(BigDecimal(100)).divide(previous, 1, RoundingMode.HALF_UP).toDouble()

    private data class DashTotals(
        val gross: BigDecimal,
        val expected: BigDecimal,
        val received: BigDecimal,
        val divergence: BigDecimal,
        val loss: BigDecimal,
        val recoverable: BigDecimal,
        val pendingSettlement: BigDecimal,
        val pendingInvoices: Int,
        val avgTicket: BigDecimal,
        val items: Int,
        val reconciledPct: Double,
        val divergentPct: Double,
        val pendingPct: Double,
    )
}
