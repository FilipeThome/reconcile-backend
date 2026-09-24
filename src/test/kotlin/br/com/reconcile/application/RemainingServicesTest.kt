package br.com.reconcile.application

import br.com.reconcile.TestAuth
import br.com.reconcile.closing.api.ClosingControllers
import br.com.reconcile.closing.application.ClosingService
import br.com.reconcile.closing.application.ReportService
import br.com.reconcile.closing.infra.ClosingDeltaRepository
import br.com.reconcile.closing.infra.ClosingEntity
import br.com.reconcile.closing.infra.ClosingRepository
import br.com.reconcile.closing.infra.ExportRepository
import br.com.reconcile.fiscal.api.InvoiceController
import br.com.reconcile.fiscal.infra.InvoiceEntity
import br.com.reconcile.fiscal.infra.InvoiceRepository
import br.com.reconcile.fiscal.infra.StubFiscalAdapter
import br.com.reconcile.identity.api.AuthController
import br.com.reconcile.identity.api.LoginRequest
import br.com.reconcile.identity.api.SettingsController
import br.com.reconcile.identity.application.AuthService
import br.com.reconcile.identity.infra.SellerAccountEntity
import br.com.reconcile.identity.infra.SellerAccountRepository
import br.com.reconcile.identity.infra.TenantEntity
import br.com.reconcile.identity.infra.TenantRepository
import br.com.reconcile.identity.infra.UserEntity
import br.com.reconcile.identity.infra.UserRepository
import br.com.reconcile.integration.api.IntegrationController
import br.com.reconcile.integration.application.IntegrationService
import br.com.reconcile.integration.ingestion.IngestionResult
import br.com.reconcile.integration.ingestion.MarketplaceIngestionService
import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.infra.IntegrationSyncRepository
import br.com.reconcile.integration.infra.RawEventRepository
import br.com.reconcile.ledger.api.LedgerControllers
import br.com.reconcile.ledger.application.LedgerQueryService
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
import br.com.reconcile.reconciliation.infra.CaseEntity
import br.com.reconcile.reconciliation.infra.CaseRepository
import br.com.reconcile.reconciliation.infra.SnapshotEntity
import br.com.reconcile.reconciliation.infra.SnapshotRepository
import br.com.reconcile.shared.api.NotFoundException
import br.com.reconcile.shared.api.PeriodFilter
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import br.com.reconcile.shared.domain.Money
import br.com.reconcile.shared.security.ClientIpResolver
import br.com.reconcile.shared.security.JwtService
import br.com.reconcile.shared.security.LoginRateLimiter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemainingServicesTest {
    private val tenant = TestAuth.tenantId
    private val orderId = UUID.fromString("10000000-0000-4000-8000-000000001234")
    private val paymentId = UUID.fromString("20000000-0000-4000-8000-000000000001")
    private val payoutId = UUID.fromString("40000000-0000-4000-8000-000000000001")
    private val orders = Mockito.mock(OrderRepository::class.java)
    private val payments = Mockito.mock(PaymentRepository::class.java)
    private val settlements = Mockito.mock(SettlementRepository::class.java)
    private val payouts = Mockito.mock(PayoutRepository::class.java)
    private val payoutOrders = Mockito.mock(PayoutOrderRepository::class.java)
    private val fees = Mockito.mock(FeeRepository::class.java)
    private val refunds = Mockito.mock(RefundRepository::class.java)
    private val invoices = Mockito.mock(InvoiceRepository::class.java)
    private val cases = Mockito.mock(CaseRepository::class.java)
    private val snapshots = Mockito.mock(SnapshotRepository::class.java)
    private val audit = Mockito.mock(AuditService::class.java)

    private val order = OrderEntity(
        id = orderId,
        tenantId = tenant,
        externalId = "PED-1",
        orderedAt = Instant.parse("2026-05-04T11:22:00Z"),
        marketplace = "Mercado Livre",
        grossAmount = Money.of("100"),
        expectedNet = Money.of("80"),
        actualNet = Money.of("80"),
    )
    private val payment = PaymentEntity(
        id = paymentId,
        tenantId = tenant,
        externalId = "PAY-1",
        orderId = orderId,
        paidAt = Instant.parse("2026-05-04T11:24:00Z"),
        gateway = "Mercado Pago",
    )

    @BeforeEach
    fun setup() = TestAuth.login()

    @AfterEach
    fun cleanup() = TestAuth.logout()

    @Test
    fun `ledger lists and gets related records`() {
        val payout = PayoutEntity(id = payoutId, tenantId = tenant, externalId = "REP-1", gateway = "Mercado Pago", paidAt = Instant.parse("2026-05-10T00:00:00Z"))
        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(listOf(order))
        Mockito.`when`(orders.findByTenantIdAndExternalId(tenant, "PED-1")).thenReturn(order)
        Mockito.`when`(orders.findById(orderId)).thenReturn(Optional.of(order))
        Mockito.`when`(payments.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(listOf(payment))
        Mockito.`when`(payments.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(listOf(payment))
        Mockito.`when`(payments.findById(paymentId)).thenReturn(Optional.of(payment))
        Mockito.`when`(settlements.findByTenantId(tenant)).thenReturn(
            listOf(SettlementEntity(tenantId = tenant, externalId = "SET-1", paymentId = paymentId, orderId = orderId, settledOn = LocalDate.parse("2026-05-08"))),
        )
        Mockito.`when`(settlements.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(
            SettlementEntity(tenantId = tenant, externalId = "SET-1", paymentId = paymentId, orderId = orderId),
        )
        Mockito.`when`(payouts.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(listOf(payout))
        Mockito.`when`(payouts.findById(payoutId)).thenReturn(Optional.of(payout))
        Mockito.`when`(payoutOrders.findByPayoutId(payoutId)).thenReturn(listOf(PayoutOrderEntity(payoutId, orderId)))
        Mockito.`when`(payoutOrders.findByOrderId(orderId)).thenReturn(listOf(PayoutOrderEntity(payoutId, orderId)))
        Mockito.`when`(fees.findByTenantId(tenant)).thenReturn(listOf(FeeEntity(tenantId = tenant, externalId = "FEE-1", orderId = orderId, occurredAt = Instant.parse("2026-05-04T12:00:00Z"))))
        Mockito.`when`(refunds.findByTenantId(tenant)).thenReturn(listOf(RefundEntity(tenantId = tenant, externalId = "REF-1", orderId = orderId, occurredAt = Instant.parse("2026-05-06T12:00:00Z"))))
        Mockito.`when`(invoices.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(InvoiceEntity(tenantId = tenant, externalId = "NFE-1", orderId = orderId))
        Mockito.`when`(cases.findByTenantIdAndOrderExternalId(tenant, "PED-1")).thenReturn(CaseEntity(externalId = "DIV-1"))
        Mockito.`when`(snapshots.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(SnapshotEntity(externalId = "SNAP-1", orderId = orderId))

        val ledger = LedgerQueryService(orders, payments, settlements, payouts, payoutOrders, fees, refunds, invoices, cases, snapshots)
        val filter = PeriodFilter.of("2026-05-01", "2026-05-31", "Mercado Livre", "Mercado Pago")
        assertEquals(1, ledger.listOrders(filter).size)
        assertEquals("PED-1", ledger.getOrder("PED-1")["id"])
        assertEquals(1, ledger.listPayments(filter).size)
        assertEquals(1, ledger.listSettlements(filter).size)
        assertEquals(1, ledger.listPayouts(filter).size)
        assertEquals(1, ledger.listFees(filter).size)
        assertEquals(1, ledger.listRefunds(filter).size)
        assertThrows<NotFoundException> { ledger.getOrder("missing") }

        val api = LedgerControllers(ledger)
        api.orders("2026-05-01", "2026-05-31", null, null)
        api.order("PED-1")
        api.payments(null, null, null, null)
        api.settlements(null, null, null, null)
        api.payouts(null, null, null, null)
        api.fees(null, null, null, null)
        api.refunds(null, null, null, null)
    }

    @Test
    fun `closing and reports`() {
        val closings = Mockito.mock(ClosingRepository::class.java)
        val deltas = Mockito.mock(ClosingDeltaRepository::class.java)
        val exports = Mockito.mock(ExportRepository::class.java)
        val item = ClosingEntity(tenantId = tenant, externalId = "CLO-1", period = "2026-05", status = "open", label = "Maio")
        Mockito.`when`(closings.findByTenantIdOrderByPeriodDesc(tenant)).thenReturn(listOf(item))
        Mockito.`when`(closings.findByTenantIdAndExternalId(tenant, "CLO-1")).thenReturn(item)
        Mockito.`when`(deltas.findByClosingId(item.id)).thenReturn(emptyList())
        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(listOf(order))
        Mockito.`when`(cases.findByTenantIdOrderByOpenedAtDesc(tenant)).thenReturn(
            listOf(CaseEntity(orderExternalId = "PED-1", cause = "nota_ausente", amount = Money.of("10"))),
        )
        val service = ClosingService(closings, deltas, orders, cases, audit)
        assertEquals(1, service.list().size)
        assertTrue(service.get("CLO-1").containsKey("causes"))
        service.close("CLO-1")
        assertEquals("closed", item.status)
        service.lateEvent("CLO-1", "payout tardio", Money.of("5"))
        assertThrows<NotFoundException> { service.get("x") }

        val reports = ReportService(exports, audit)
        Mockito.`when`(exports.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(emptyList())
        assertTrue(reports.export("fin")["csv"]!!.contains("fin"))
        assertThrows<UnprocessableException> { reports.export("BAD TYPE") }
        assertTrue(reports.history().isEmpty())

        val api = ClosingControllers(service, reports)
        api.list()
        api.get("CLO-1")
        api.history()
        api.export("fin")
    }

    @Test
    fun `auth settings integration and invoices`() {
        val users = Mockito.mock(UserRepository::class.java)
        val tenants = Mockito.mock(TenantRepository::class.java)
        val sellers = Mockito.mock(SellerAccountRepository::class.java)
        val encoder = Mockito.mock(PasswordEncoder::class.java)
        val jwt = JwtService(TestAuth.JWT_SECRET, 1)
        val user = UserEntity(id = TestAuth.userId, tenantId = tenant, name = "Ana", email = "ana@x.com", passwordHash = "hash", role = "admin")
        val tenantEntity = TenantEntity(id = tenant, externalId = "ten_8f2c1a", name = "Loja")
        Mockito.`when`(users.findByEmail("ana@x.com")).thenReturn(user)
        Mockito.`when`(encoder.matches("senha", "hash")).thenReturn(true)
        Mockito.`when`(tenants.findById(tenant)).thenReturn(Optional.of(tenantEntity))
        val auth = AuthService(users, tenants, encoder, jwt)
        assertEquals("Ana", auth.login("ana@x.com", "senha").name)
        Mockito.`when`(users.findByEmail("nope")).thenReturn(null)
        assertThrows<BadCredentialsException> { auth.login("nope", "x") }
        Mockito.`when`(encoder.matches("bad", "hash")).thenReturn(false)
        assertThrows<BadCredentialsException> { auth.login("ana@x.com", "bad") }

        val request = MockHttpServletRequest()
        request.remoteAddr = "127.0.0.1"
        request.addHeader("X-Real-IP", "10.0.0.8")
        val loginApi = AuthController(auth, LoginRateLimiter(), ClientIpResolver(""))
        loginApi.login(LoginRequest("ana@x.com", "senha"), request)

        Mockito.`when`(users.findByTenantId(tenant)).thenReturn(listOf(user))
        Mockito.`when`(sellers.findByTenantId(tenant)).thenReturn(listOf(SellerAccountEntity(sellerExternalId = "MLB-1")))
        val settings = SettingsController(tenants, users, sellers).settings()
        assertEquals("Loja", (settings["tenant"] as Map<*, *>)["name"])
        assertEquals(1, (settings["users"] as List<*>).size)
        TestAuth.login("operador")
        val operatorSettings = SettingsController(tenants, users, sellers).settings()
        val operatorUsers = operatorSettings["users"] as List<*>
        assertEquals(1, operatorUsers.size)
        assertEquals("operador", (operatorUsers.single() as Map<*, *>)["role"])

        val connections = Mockito.mock(ConnectionRepository::class.java)
        val syncs = Mockito.mock(IntegrationSyncRepository::class.java)
        val events = Mockito.mock(RawEventRepository::class.java)
        val oauth = Mockito.mock(br.com.reconcile.integration.oauth.MarketplaceOAuthService::class.java)
        val sellerAccounts = Mockito.mock(SellerAccountRepository::class.java)
        val connection = ConnectionEntity(tenantId = tenant, externalId = "ml", name = "ML", status = "disponivel")
        val csv = ConnectionEntity(tenantId = tenant, externalId = "csv", name = "CSV", status = "disponivel")
        val shopee = ConnectionEntity(tenantId = tenant, externalId = "shopee", name = "Shopee", status = "disponivel")
        Mockito.`when`(connections.findByTenantId(tenant)).thenReturn(listOf(connection))
        Mockito.`when`(connections.findByTenantIdAndExternalId(tenant, "ml")).thenReturn(connection)
        Mockito.`when`(connections.findByTenantIdAndExternalId(tenant, "csv")).thenReturn(csv)
        Mockito.`when`(connections.findByTenantIdAndExternalId(tenant, "shopee")).thenReturn(shopee)
        Mockito.`when`(oauth.isOAuthProvider("ml")).thenReturn(true)
        Mockito.`when`(oauth.isOAuthProvider("csv")).thenReturn(false)
        Mockito.`when`(oauth.isOAuthProvider("x")).thenReturn(false)
        Mockito.`when`(oauth.configuredHint("ml")).thenReturn(null)
        Mockito.`when`(oauth.authorizationUrl("ml", tenant, TestAuth.userId)).thenReturn(
            br.com.reconcile.integration.oauth.OAuthStart("https://auth.mercadolivre.com.br/authorization?response_type=code"),
        )
        Mockito.`when`(
            events.existsByTenantIdAndIdempotencyKey(Mockito.any(UUID::class.java) ?: tenant, Mockito.anyString() ?: ""),
        ).thenReturn(false)
        Mockito.`when`(oauth.configuredHint("ml")).thenReturn("Defina MERCADO_LIVRE_CLIENT_ID")
        val ingestion = Mockito.mock(MarketplaceIngestionService::class.java)
        Mockito.`when`(ingestion.supports("ml")).thenReturn(true)
        Mockito.`when`(ingestion.ingest(connection)).thenReturn(IngestionResult(2, 2, 1, 0, 1))
        val integrations = IntegrationService(connections, syncs, events, audit, oauth, sellerAccounts, ingestion)
        assertTrue(integrations.list()[0]["detail"].toString().contains("MERCADO_LIVRE"))
        Mockito.`when`(oauth.configuredHint("ml")).thenReturn(null)
        val oauthStart = integrations.connect("ml")
        assertTrue(oauthStart["authorizationUrl"].toString().contains("auth.mercadolivre.com.br"))
        integrations.connect("csv")
        integrations.disconnect("ml")
        integrations.activateFromOAuth(
            br.com.reconcile.integration.oauth.OAuthTokens("ml", tenant, "MLB-1", "tok", "ref", Instant.parse("2026-05-04T12:00:00Z")),
        )
        Mockito.`when`(sellerAccounts.findByTenantIdAndMarketplace(tenant, "Mercado Livre")).thenReturn(
            SellerAccountEntity(tenantId = tenant, marketplace = "Mercado Livre", sellerExternalId = "old"),
        )
        integrations.activateFromOAuth(
            br.com.reconcile.integration.oauth.OAuthTokens("ml", tenant, "MLB-2", "tok", "ref", Instant.parse("2026-05-04T12:00:00Z")),
        )
        assertTrue(integrations.list()[0]["detail"].toString().contains("MLB-2"))
        integrations.activateFromOAuth(
            br.com.reconcile.integration.oauth.OAuthTokens("shopee", tenant, "44120", "tok2", null, null),
        )
        integrations.backfill("ml")
        Mockito.`when`(
            events.existsByTenantIdAndIdempotencyKey(Mockito.any(UUID::class.java) ?: tenant, Mockito.anyString() ?: ""),
        ).thenReturn(true)
        integrations.backfill("ml")
        assertThrows<NotFoundException> { integrations.connect("x") }
        IntegrationController(integrations).list()

        Mockito.`when`(orders.findByTenantIdOrderByOrderedAtDesc(tenant)).thenReturn(listOf(order))
        Mockito.`when`(invoices.findByTenantId(tenant)).thenReturn(
            listOf(InvoiceEntity(tenantId = tenant, orderId = orderId, issuedAt = Instant.parse("2026-05-04T12:00:00Z"), accessKey = "k")),
        )
        Mockito.`when`(invoices.findByTenantIdAndOrderId(tenant, orderId)).thenReturn(null)
        Mockito.`when`(payments.findByTenantIdOrderByPaidAtDesc(tenant)).thenReturn(
            listOf(PaymentEntity(tenantId = tenant, orderId = orderId, gateway = "Mercado Pago")),
        )
        val fiscal = InvoiceController(invoices, orders, payments, StubFiscalAdapter())
            .list("2026-05-01", "2026-05-31", "Mercado Livre", "Mercado Pago")
        assertTrue((fiscal["documents"] as List<*>).isNotEmpty())
    }
}
