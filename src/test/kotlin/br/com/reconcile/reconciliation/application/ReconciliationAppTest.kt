package br.com.reconcile.reconciliation.application

import br.com.reconcile.TestAuth
import br.com.reconcile.closing.infra.ClosingEntity
import br.com.reconcile.closing.infra.ClosingRepository
import br.com.reconcile.fiscal.infra.InvoiceEntity
import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.identity.infra.TenantEntity
import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.ledger.infra.FeeEntity
import br.com.reconcile.ledger.infra.FeeRepository
import br.com.reconcile.ledger.infra.OrderEntity
import br.com.reconcile.ledger.infra.OrderRepository
import br.com.reconcile.ledger.infra.PaymentEntity
import br.com.reconcile.ledger.infra.PaymentRepository
import br.com.reconcile.ledger.infra.PayoutEntity
import br.com.reconcile.ledger.infra.PayoutOrderEntity
import br.com.reconcile.ledger.infra.PayoutOrderRepository
import br.com.reconcile.ledger.infra.PayoutRepository
import br.com.reconcile.ledger.infra.RefundEntity
import br.com.reconcile.ledger.infra.RefundRepository
import br.com.reconcile.ledger.infra.SettlementEntity
import br.com.reconcile.ledger.infra.SettlementRepository
import br.com.reconcile.reconciliation.api.CommentRequest
import br.com.reconcile.reconciliation.api.ReconciliationControllers
import br.com.reconcile.reconciliation.api.StatusRequest
import br.com.reconcile.reconciliation.infra.CaseCommentEntity
import br.com.reconcile.reconciliation.infra.CaseCommentRepository
import br.com.reconcile.reconciliation.infra.CaseEntity
import br.com.reconcile.reconciliation.infra.CaseRepository
import br.com.reconcile.reconciliation.infra.CaseTimelineRepository
import br.com.reconcile.reconciliation.infra.RuleEntity
import br.com.reconcile.reconciliation.infra.RuleRepository
import br.com.reconcile.reconciliation.infra.SnapshotEntity
import br.com.reconcile.reconciliation.infra.SnapshotRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.PeriodFilter
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.domain.Money
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconciliationAppTest {
    private val tenant = TestAuth.tenantId
    private val cases = Mockito.mock(CaseRepository::class.java)
    private val comments = Mockito.mock(CaseCommentRepository::class.java)
    private val timeline = Mockito.mock(CaseTimelineRepository::class.java)
    private val snapshots = Mockito.mock(SnapshotRepository::class.java)
    private val orders = Mockito.mock(OrderRepository::class.java)
    private val payments = Mockito.mock(PaymentRepository::class.java)
    private val audit = Mockito.mock(AuditService::class.java)
    private val rules = Mockito.mock(RuleRepository::class.java)
    private val closings = Mockito.mock(ClosingRepository::class.java)
    private val invoices = Mockito.mock(InvoiceRepository::class.java)
    private val payouts = Mockito.mock(PayoutRepository::class.java)
    private val tenants = Mockito.mock(TenantRepository::class.java)
    private val settlements = Mockito.mock(SettlementRepository::class.java)
    private val payoutOrders = Mockito.mock(PayoutOrderRepository::class.java)
    private val refunds = Mockito.mock(RefundRepository::class.java)
    private val fees = Mockito.mock(FeeRepository::class.java)

    private val orderId = UUID.fromString("10000000-0000-4000-8000-000000001234")
    private val order = OrderEntity(
        id = orderId,
        tenantId = tenant,
        externalId = "PED-1",
        orderedAt = Instant.parse("2026-05-04T11:22:00Z"),
        buyerName = "Ana",
        marketplace = "Mercado Livre",
        items = 1,
        grossAmount = Money.of("1000"),
        expectedNet = Money.of("842.31"),
        actualNet = null,
        reconciliationStatus = "divergente",
    )
    private val payment = PaymentEntity(
        tenantId = tenant,
        externalId = "PAY-1",
        orderId = orderId,
        paidAt = Instant.parse("2026-05-04T11:24:00Z"),
        gateway = "Mercado Pago",
        method = "Pix",
        status = "aprovado",
    )
    private val caseEntity = CaseEntity(
        tenantId = tenant,
        externalId = "DIV-1",
        openedAt = Instant.parse("2026-05-19T09:10:00Z"),
        orderExternalId = "PED-1",
        cause = "repasse_nao_realizado",
        description = "sem payout",
        amount = Money.of("842.31"),
        status = "aberta",
    )

    @BeforeEach
    fun setup() {
        TestAuth.login()
    }

    @AfterEach
    fun cleanup() {
        TestAuth.logout()
    }

    @Test
    fun `case service lists, gets, comments and rejects bad input`() {
        val service = CaseService(cases, comments, timeline, snapshots, orders, payments, audit)
        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(listOf(order))
        Mockito.`when`(payments.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(listOf(payment))
        Mockito.`when`(cases.findByTenantIdOrderByOpenedAtDesc(tenant)).thenReturn(listOf(caseEntity))
        Mockito.`when`(cases.findByTenantIdAndExternalId(tenant, "DIV-1")).thenReturn(caseEntity)
        Mockito.`when`(orders.findByTenantIdAndExternalId(tenant, "PED-1")).thenReturn(order)
        Mockito.`when`(snapshots.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(
            SnapshotEntity(tenantId = tenant, externalId = "SNAP-1", orderId = orderId, components = "[]"),
        )
        Mockito.`when`(comments.findByCaseIdOrderByCreatedAtAsc(caseEntity.id)).thenReturn(
            listOf(CaseCommentEntity(caseId = caseEntity.id, author = "Ana", body = "visto")),
        )
        Mockito.`when`(timeline.findByCaseIdOrderByOccurredAtAsc(caseEntity.id)).thenReturn(emptyList())

        val listed = service.list("aberta", PeriodFilter.of("2026-05-01", "2026-05-31", "Mercado Livre", "Mercado Pago"))
        assertEquals(1, listed.size)
        assertEquals("DIV-1", service.get("DIV-1")["id"])
        service.updateStatus("DIV-1", "em_analise")
        service.addComment("DIV-1", "ok")
        assertThrows<UnprocessableException> { service.addComment("DIV-1", "  ") }
        assertThrows<UnprocessableException> { service.updateStatus("DIV-1", "nao_existe") }
        assertThrows<NotFoundException> { service.get("missing") }

        val ignored = service.list("resolvida", PeriodFilter.of("2026-04-01", "2026-04-30", "Shopee", null))
        assertTrue(ignored.isEmpty())
    }

    @Test
    fun `rules and snapshots`() {
        val rule = RuleEntity(tenantId = tenant, externalId = "r1", name = "R1", active = true)
        Mockito.`when`(rules.findByTenantId(tenant)).thenReturn(listOf(rule))
        Mockito.`when`(rules.findByTenantIdAndExternalId(tenant, "r1")).thenReturn(rule)
        val ruleService = RuleQueryService(rules, audit)
        assertEquals(true, ruleService.list().first()["active"])
        ruleService.toggle("r1")
        assertEquals(false, rule.active)
        assertThrows<NotFoundException> { ruleService.toggle("x") }

        val snap = SnapshotEntity(tenantId = tenant, externalId = "SNAP-1", orderId = orderId)
        Mockito.`when`(snapshots.findByTenantId(tenant)).thenReturn(listOf(snap))
        Mockito.`when`(snapshots.findByTenantIdAndExternalId(tenant, "SNAP-1")).thenReturn(snap)
        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(listOf(order))
        Mockito.`when`(orders.findById(orderId)).thenReturn(Optional.of(order))
        val snaps = SnapshotQueryService(snapshots, orders)
        assertEquals("SNAP-1", snaps.list().first()["id"])
        assertEquals("PED-1", snaps.get("SNAP-1")["orderId"])
        assertThrows<NotFoundException> { snaps.get("missing") }
    }

    @Test
    fun `dashboard computes empty and populated recortes`() {
        val service = DashboardService(cases, closings, orders, payments, invoices, payouts)
        Mockito.`when`(closings.findByTenantIdOrderByPeriodDesc(tenant)).thenReturn(
            listOf(ClosingEntity(tenantId = tenant, period = "2026-05", status = "open")),
        )
        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(emptyList())
        Mockito.`when`(payments.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(emptyList())
        Mockito.`when`(invoices.findByTenantId(tenant)).thenReturn(emptyList())
        Mockito.`when`(cases.findByTenantIdOrderByOpenedAtDesc(tenant)).thenReturn(emptyList())
        Mockito.`when`(payouts.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(emptyList())
        val empty = service.dashboard(PeriodFilter.of(null, null, null, null))
        assertEquals("01/05/2026 — 31/05/2026", empty["periodLabel"])

        val paid = OrderEntity(
            id = UUID.fromString("10000000-0000-4000-8000-000000001299"),
            tenantId = tenant,
            externalId = "PED-2",
            orderedAt = Instant.parse("2026-05-05T11:22:00Z"),
            marketplace = "Mercado Livre",
            items = 1,
            grossAmount = Money.of("200"),
            expectedNet = Money.of("180"),
            actualNet = Money.of("180"),
            reconciliationStatus = "reconciliado",
        )
        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(listOf(paid, order))
        Mockito.`when`(payments.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(listOf(payment))
        Mockito.`when`(invoices.findByTenantId(tenant)).thenReturn(listOf(InvoiceEntity(tenantId = tenant, orderId = orderId)))
        Mockito.`when`(cases.findByTenantIdOrderByOpenedAtDesc(tenant)).thenReturn(
            listOf(
                caseEntity,
                CaseEntity(tenantId = tenant, externalId = "DIV-2", orderExternalId = "PED-1", cause = "estorno_parcial"),
            ),
        )
        Mockito.`when`(payouts.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(
            listOf(PayoutEntity(tenantId = tenant, externalId = "REP-1", status = "previsto", gateway = "Mercado Pago")),
        )
        val filled = service.dashboard(PeriodFilter.of("2026-05-01", "2026-05-31", null, null))
        assertTrue((filled["actionQueue"] as List<*>).isNotEmpty())
    }

    @Test
    fun `engine overview and reprocess`() {
        val tenantEntity = TenantEntity(id = tenant, externalId = "ten_8f2c1a", invoiceRequired = true)
        Mockito.`when`(tenants.findById(tenant)).thenReturn(Optional.of(tenantEntity))
        Mockito.`when`(snapshots.findByTenantId(tenant)).thenReturn(
            listOf(SnapshotEntity(tenantId = tenant, externalId = "SNAP-001", orderId = orderId)),
        )
        Mockito.`when`(fees.findByTenantId(tenant)).thenReturn(
            listOf(FeeEntity(tenantId = tenant, feeType = "Frete", actualAmount = Money.of("10"))),
        )
        Mockito.`when`(orders.findById(orderId)).thenReturn(Optional.of(order))
        val engine = EngineService(
            tenants, orders, payments, settlements, payoutOrders, invoices, refunds, fees, cases, rules, timeline, snapshots, audit,
        )
        val overview = engine.overview()
        assertEquals("sce-v1.4", overview["version"])

        Mockito.`when`(orders.findByTenantIdAndExternalId(tenant, "PED-1")).thenReturn(order)
        Mockito.`when`(payments.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(listOf(payment))
        Mockito.`when`(settlements.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(null)
        Mockito.`when`(payoutOrders.findByOrderId(orderId)).thenReturn(emptyList())
        Mockito.`when`(invoices.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(null)
        Mockito.`when`(refunds.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(emptyList())
        Mockito.`when`(fees.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(emptyList())
        Mockito.`when`(rules.findByTenantId(tenant)).thenReturn(listOf(RuleEntity(externalId = "r1", active = true)))
        Mockito.`when`(cases.findByTenantIdAndOrderExternalId(tenant, "PED-1")).thenReturn(null)
        Mockito.`when`(cases.save(Mockito.any(CaseEntity::class.java) ?: CaseEntity())).thenAnswer { it.arguments[0] }
        val result = engine.reprocess("PED-1")
        assertEquals("PED-1", result["orderId"])
        assertThrows<NotFoundException> { engine.reprocess("missing") }

        Mockito.`when`(orders.findByTenantIdAndExternalId(tenant, "PED-1")).thenReturn(order)
        Mockito.`when`(fees.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(
            listOf(FeeEntity(feeType = "Comissão marketplace", actualAmount = Money.of("12"))),
        )
        Mockito.`when`(refunds.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(
            listOf(RefundEntity(amount = Money.of("10"), reflectedInSettlement = false)),
        )
        Mockito.`when`(cases.findByTenantIdAndOrderExternalId(tenant, "PED-1")).thenReturn(caseEntity)
        engine.reprocess("PED-1")
    }

    @Test
    fun `controllers delegate`() {
        val caseService = Mockito.mock(CaseService::class.java)
        val ruleService = Mockito.mock(RuleQueryService::class.java)
        val engine = Mockito.mock(EngineService::class.java)
        val dashboard = Mockito.mock(DashboardService::class.java)
        val snapService = Mockito.mock(SnapshotQueryService::class.java)
        Mockito.`when`(caseService.get("DIV-1")).thenReturn(mapOf("orderId" to "PED-1"))
        Mockito.`when`(engine.reprocess("PED-1")).thenReturn(mapOf("ok" to true))
        val api = ReconciliationControllers(caseService, ruleService, engine, dashboard, snapService)
        api.dashboard(null, null, null, null)
        api.list(null, null, null, null, null)
        api.get("DIV-1")
        api.status("DIV-1", StatusRequest("resolvida"))
        api.comment("DIV-1", CommentRequest("ok"))
        api.reprocess("DIV-1")
        api.engine()
        api.snapshots()
        api.snapshot("SNAP-1")
        api.rules()
        api.toggleRule("r1")
    }
}
