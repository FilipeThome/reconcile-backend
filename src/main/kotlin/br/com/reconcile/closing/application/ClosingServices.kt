package br.com.reconcile.closing.application

import br.com.reconcile.closing.domain.ClosingSnapshot
import br.com.reconcile.closing.domain.ClosingStateMachine
import br.com.reconcile.closing.domain.ClosingStatus
import br.com.reconcile.closing.infra.ClosingDeltaEntity
import br.com.reconcile.closing.infra.ClosingDeltaRepository
import br.com.reconcile.closing.infra.ClosingEntity
import br.com.reconcile.closing.infra.ClosingRepository
import br.com.reconcile.closing.infra.ExportEntity
import br.com.reconcile.closing.infra.ExportRepository
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.reconciliation.infra.CaseRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.security.currentUser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@Service
class ClosingService(
    private val closings: ClosingRepository,
    private val deltas: ClosingDeltaRepository,
    private val orders: OrderRepository,
    private val cases: CaseRepository,
    private val audit: AuditService,
) {
    fun list() = closings.findByTenantIdOrderByPeriodDesc(currentUser().tenantId).map { toDto(it) }

    fun get(externalId: String): Map<String, Any?> {
        val item = requireClosing(externalId)
        return toDto(item) + mapOf(
            "deltas" to deltas.findByClosingId(item.id).map {
                mapOf("reason" to it.reason, "amount" to it.amount, "createdAt" to it.createdAt.toString())
            },
            "causes" to causesFor(item.period),
        )
    }

    @Transactional
    fun close(externalId: String) {
        val item = requireClosing(externalId)
        val closed = ClosingStateMachine.close(
            ClosingSnapshot(ClosingStatus.valueOf(item.status), item.closedAt, item.expectedAmount, item.receivedAmount),
            Instant.now(),
        )
        item.status = closed.status.name
        item.closedAt = closed.closedAt
        closings.save(item)
        val user = currentUser()
        audit.record(user.tenantId, user.displayName, "closing.close", "closing", item.externalId)
    }

    @Transactional
    fun lateEvent(externalId: String, reason: String, amount: BigDecimal) {
        val item = requireClosing(externalId)
        val delta = ClosingStateMachine.lateEvent(
            ClosingSnapshot(ClosingStatus.valueOf(item.status), item.closedAt, item.expectedAmount, item.receivedAmount),
            reason,
            amount,
            Instant.now(),
        )
        deltas.save(
            ClosingDeltaEntity(
                tenantId = item.tenantId,
                closingId = item.id,
                reason = delta.reason,
                amount = delta.amount,
                createdAt = delta.createdAt,
            ),
        )
    }

    private fun causesFor(period: String): List<Map<String, Any>> {
        val month = runCatching { YearMonth.parse(period) }.getOrNull() ?: return emptyList()
        val tenant = currentUser().tenantId
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val orderIds = orders.findByTenantIdOrderByOrderedAtDesc(tenant)
            .filter {
                val day = it.orderedAt.atZone(ZoneOffset.UTC).toLocalDate()
                !day.isBefore(from) && !day.isAfter(to)
            }
            .map { it.externalId }
            .toSet()
        val grouped = cases.findByTenantIdOrderByOpenedAtDesc(tenant)
            .filter { it.orderExternalId in orderIds }
            .groupBy { causeLabel(it.cause) }
            .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, item -> acc + item.amount } }
            .entries
            .sortedByDescending { it.value }
        val total = grouped.fold(BigDecimal.ZERO) { acc, item -> acc + item.value }
        return grouped.map { (cause, value) ->
            mapOf(
                "cause" to cause,
                "value" to value,
                "pct" to if (total.compareTo(BigDecimal.ZERO) == 0) 0.0
                else value.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toDouble(),
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

    private fun requireClosing(externalId: String) =
        closings.findByTenantIdAndExternalId(currentUser().tenantId, externalId)
            ?: throw NotFoundException("Fechamento $externalId não encontrado")

    private fun toDto(item: ClosingEntity) = mapOf(
        "id" to item.externalId,
        "period" to item.period,
        "label" to item.label,
        "status" to item.status,
        "closedAt" to item.closedAt?.toString(),
        "orders" to item.ordersCount,
        "gross" to item.grossAmount,
        "expected" to item.expectedAmount,
        "received" to item.receivedAmount,
        "fees" to item.feesAmount,
        "refunds" to item.refundsAmount,
        "openDiff" to item.openDiffAmount,
        "pendingInvoices" to item.pendingInvoices,
    )
}

@Service
class ReportService(private val exports: ExportRepository, private val audit: AuditService) {
    @Transactional
    fun export(type: String): Map<String, String> {
        val safeType = type.lowercase()
        if (!safeType.matches(Regex("^[a-z0-9_-]{1,40}$"))) {
            throw UnprocessableException("Tipo de relatório inválido")
        }
        val user = currentUser()
        val filename = "$safeType-${Instant.now().epochSecond}.csv"
        exports.save(ExportEntity(tenantId = user.tenantId, reportType = safeType, filename = filename, createdBy = user.displayName))
        audit.record(user.tenantId, user.displayName, "report.export", "export", safeType)
        return mapOf("filename" to filename, "csv" to "tipo;gerado_por;arquivo\n$safeType;${user.displayName};$filename")
    }

    fun history() = exports.findByTenantIdOrderByCreatedAtDesc(currentUser().tenantId).map {
        mapOf("type" to it.reportType, "filename" to it.filename, "createdBy" to it.createdBy, "createdAt" to it.createdAt.toString())
    }
}
