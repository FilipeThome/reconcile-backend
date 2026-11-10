package br.com.reconcile.integration.stocksync

import br.com.reconcile.TestAuth
import br.com.reconcile.integration.api.ManualSaleRequest
import br.com.reconcile.integration.api.MarketplaceWebhookController
import br.com.reconcile.integration.api.SaleAlertController
import br.com.reconcile.integration.infra.ConnectionEntity
import br.com.reconcile.integration.infra.ConnectionRepository
import br.com.reconcile.integration.infra.RawEventEntity
import br.com.reconcile.integration.infra.RawEventRepository
import br.com.reconcile.integration.infra.SaleEventEntity
import br.com.reconcile.integration.infra.SaleEventRepository
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.audit.AuditService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun <T> anyOf(type: Class<T>, fallback: T): T = Mockito.any(type) ?: fallback

private fun text(fallback: String = ""): String = Mockito.anyString() ?: fallback

class FakePort(private val id: String, vararg listings: StockListing, private val quantities: Boolean = true) : MarketplaceStockPort {
    val listings = listings.toMutableList()
    val writes = mutableListOf<Int>()
    var failFind = false
    var failWrite = false

    override fun providerId() = id

    override fun tracksQuantity() = quantities

    override fun find(access: ConnectionAccess, sku: String): List<StockListing> {
        if (failFind) throw IllegalStateException("$id fora do ar")
        return listings.toList()
    }

    override fun setQuantity(access: ConnectionAccess, listing: StockListing, quantity: Int) {
        if (failWrite) throw IllegalStateException("$id recusou")
        writes.add(quantity)
        listings.replaceAll { if (it.itemId == listing.itemId) it.copy(quantity = quantity) else it }
    }
}

class SaleStockSyncTest {
    private val tenant = TestAuth.tenantId
    private val sales = Mockito.mock(SaleEventRepository::class.java)
    private val connections = Mockito.mock(ConnectionRepository::class.java)
    private val leaser = Mockito.mock(ConnectionTokenLeaser::class.java)
    private val audit = Mockito.mock(AuditService::class.java)
    private val rows = mutableListOf<SaleEventEntity>()
    private val ml = FakePort("ml", StockListing("MLB1", null, "Camisa ML", 10))
    private val shopee = FakePort("shopee", StockListing("1", "31", "Camisa Shopee", 4))
    private val olx = FakePort("olx", StockListing("ABC", null, "Anúncio OLX", 1), quantities = false)
    private val service = SaleStockSyncService(sales, connections, leaser, audit, listOf(ml, shopee, olx))
    private val active = listOf("ml", "shopee", "olx", "csv").map { ConnectionEntity(tenantId = tenant, externalId = it, status = "ativa") } +
        ConnectionEntity(tenantId = tenant, externalId = "amazon", status = "disponivel")

    @BeforeEach
    fun setUp() {
        TestAuth.login()
        Mockito.`when`(connections.findByTenantId(tenant)).thenReturn(active)
        Mockito.`when`(leaser.lease(anyOf(UUID::class.java, tenant))).thenAnswer { call ->
            val connection = active.first { it.id == call.getArgument<UUID>(0) }
            ConnectionAccess(connection.externalId, "tok", "99")
        }
        Mockito.`when`(sales.saveAndFlush(anyOf(SaleEventEntity::class.java, SaleEventEntity()))).thenAnswer { call ->
            call.getArgument<SaleEventEntity>(0).also(rows::add)
        }
        Mockito.`when`(sales.save(anyOf(SaleEventEntity::class.java, SaleEventEntity()))).thenAnswer { it.getArgument<SaleEventEntity>(0) }
        Mockito.`when`(
            sales.findByTenantIdAndProviderAndOrderExternalIdAndSku(anyOf(UUID::class.java, tenant), text(), text(), text()),
        ).thenAnswer { call ->
            rows.find { it.provider == call.getArgument<String>(1) && it.orderExternalId == call.getArgument<String>(2) && it.sku == call.getArgument<String>(3) }
        }
        Mockito.`when`(sales.findTop50ByTenantIdOrderByCreatedAtDesc(tenant)).thenAnswer { rows.toList() }
    }

    @AfterEach
    fun tearDown() {
        TestAuth.logout()
    }

    private fun sale(order: String = "2000001", provider: String = "ml", cancelled: Boolean = false, vararg lines: SaleLine = arrayOf(SaleLine("ABC", 2, "Camisa"))) =
        SaleEvent(tenant, provider, order, Instant.parse("2026-11-10T12:00:00Z"), cancelled, lines.toList())

    @Suppress("UNCHECKED_CAST")
    private fun targets() = service.list().single()["targets"] as List<TargetResult>

    @Test
    fun `a sale lowers the same sku on the other marketplaces only`() {
        service.process(sale(lines = arrayOf(SaleLine("ABC", 1, "Camisa"), SaleLine(" abc ", 1, "Camisa"), SaleLine("", 5, "sem sku"), SaleLine("X", 0, "zero"))))

        assertTrue(ml.writes.isEmpty(), "the marketplace that sold already lowered its own stock")
        assertEquals(listOf(2), shopee.writes)
        val alert = service.list().single()
        assertEquals(listOf("ml", "2000001", "ABC", 2, "vendido"), listOf("provider", "orderId", "sku", "quantity", "status").map { alert[it] })
        assertEquals(listOf(TargetResult("shopee", "1", "31", "Camisa Shopee", 4, 2, true)), targets())
    }

    @Test
    fun `a redelivered sale sends no second write to a target already ok`() {
        service.process(sale())
        service.process(sale())
        service.process(sale())

        assertEquals(listOf(2), shopee.writes)
        assertEquals(1, rows.size)
    }

    @Test
    fun `only the failed targets are retried on redelivery`() {
        shopee.failWrite = true
        assertFalse(service.process(sale(provider = "manual")))
        assertEquals(listOf(true, false), targets().map { it.ok })
        assertEquals("shopee recusou", targets().last().message)

        shopee.failWrite = false
        assertTrue(service.process(sale(provider = "manual")))
        assertEquals(listOf(8), ml.writes)
        assertEquals(listOf(2), shopee.writes)
        assertTrue(targets().all { it.ok })
    }

    @Test
    fun `unreadable quantities and unreachable marketplaces are recorded, not thrown`() {
        shopee.listings[0] = shopee.listings[0].copy(quantity = null)
        assertFalse(service.process(sale(provider = "manual")))

        val results = targets()
        assertEquals(listOf("ml", "shopee"), results.map { it.provider })
        assertEquals(listOf(true, false), results.map { it.ok })
        assertEquals(listOf(8), ml.writes)
        assertTrue(shopee.writes.isEmpty())

        shopee.failFind = true
        service.process(sale(order = "down", provider = "manual"))
        assertEquals("shopee fora do ar", rows.last().targets.let { service.list().last()["targets"] as List<*> }.filterIsInstance<TargetResult>().last().message)
    }

    @Test
    fun `an ad without quantity is removed only when the sku is sold out everywhere`() {
        service.process(sale())
        assertTrue(olx.writes.isEmpty(), "shopee still has 2 and the source 10")

        ml.listings[0] = ml.listings[0].copy(quantity = 0)
        ml.failFind = true
        service.process(sale(order = "source-unknown", lines = arrayOf(SaleLine("ABC", 1, "Camisa"))))
        assertTrue(olx.writes.isEmpty(), "an unreadable source quantity is not a sold-out")

        ml.failFind = false
        service.process(sale(order = "last", lines = arrayOf(SaleLine("ABC", 1, "Camisa"))))
        assertEquals(listOf(0), olx.writes)
        assertEquals(listOf("shopee", "olx"), rows.last().let { service.list().last()["targets"] as List<*> }.filterIsInstance<TargetResult>().map { it.provider })

        service.process(sale(order = "last", cancelled = true))
        assertEquals(1, shopee.listings.single().quantity, "the unit comes back")
        assertEquals(listOf(0), olx.writes, "a removed ad is not republished")
        assertEquals("cancelado", rows.last().status)
    }

    @Test
    fun `a cancel that cannot restore stays sold and resumes without restoring twice`() {
        service.process(sale())
        shopee.failWrite = true
        assertFalse(service.process(sale(cancelled = true)))
        assertEquals("vendido", rows.single().status)

        shopee.failWrite = false
        assertTrue(service.process(sale(cancelled = true)))
        assertEquals("cancelado", rows.single().status)
        assertEquals(listOf(2, 4), shopee.writes)
        assertTrue(targets().single().restored)

        assertTrue(service.process(sale(cancelled = true)))
        assertEquals(listOf(2, 4), shopee.writes)
    }

    @Test
    fun `stock never goes below zero and a cancel gives back only what was taken`() {
        service.process(sale(lines = arrayOf(SaleLine("ABC", 3, "Camisa"))))
        assertEquals(listOf(1), shopee.writes)

        ml.listings.clear()
        service.process(sale(cancelled = true, lines = arrayOf(SaleLine("ABC", 9, "Camisa"))))
        assertEquals(listOf(1, 4), shopee.writes)
        assertEquals("cancelado", rows.single().status)

        service.process(sale(cancelled = true))
        service.process(sale())
        assertEquals(listOf(1, 4), shopee.writes, "a cancelled sale is final")

        service.process(sale(order = "never-sold", cancelled = true))
        assertEquals(1, rows.size)

        service.process(sale(order = "big", lines = arrayOf(SaleLine("ABC", 50, "Camisa"))))
        assertEquals(0, shopee.writes.last())
        service.process(sale(order = "big", cancelled = true))
        assertEquals(4, shopee.writes.last(), "a decrement clamped at zero took 4, not 50")
    }

    @Test
    fun `a concurrent duplicate loses the idempotency claim`() {
        Mockito.`when`(sales.saveAndFlush(anyOf(SaleEventEntity::class.java, SaleEventEntity())))
            .thenThrow(DataIntegrityViolationException("duplicate"))
        service.process(sale())
        assertTrue(shopee.writes.isEmpty())
    }

    @Test
    fun `manual sale validates input and reaches every marketplace`() {
        val controller = SaleAlertController(service)
        val created = controller.register(ManualSaleRequest(" ABC ", 1, " balcão "))
        assertEquals(listOf("manual", "balcão", "ABC"), listOf("provider", "orderId", "sku").map { created.single()[it] })
        assertEquals(listOf(9), ml.writes)
        assertEquals(listOf(3), shopee.writes)
        assertEquals(1, controller.list().size)

        assertEquals(1, service.registerManual("ABC", 1, " ").size)
        assertThrows<UnprocessableException> { service.registerManual(" ", 1, null) }
        assertThrows<UnprocessableException> { service.registerManual("x".repeat(121), 1, null) }
        assertThrows<UnprocessableException> { service.registerManual("ABC", 0, null) }
        assertThrows<UnprocessableException> { service.registerManual("ABC", 10_000, null) }
    }
}

class SaleWebhookTest {
    private val tenant = TestAuth.tenantId
    private val http = FakeHttp()
    private val properties = stockProperties()
    private val connections = Mockito.mock(ConnectionRepository::class.java)
    private val events = Mockito.mock(RawEventRepository::class.java)
    private val leaser = Mockito.mock(ConnectionTokenLeaser::class.java)
    private val sync = Mockito.mock(SaleStockSyncService::class.java)
    private val oauth = MarketplaceOAuthService(properties, http, TestAuth.JWT_SECRET)
    private val stored = mutableListOf<RawEventEntity>()
    private val processed = mutableListOf<SaleEvent>()
    private val since = Instant.parse("2026-11-01T00:00:00Z")
    private val secret = "hook-secret-0123456789"
    private var settles = true
    private val ml = ConnectionEntity(tenantId = tenant, externalId = "ml", status = "ativa", sellerExternalId = "99", stockSyncSince = since)
    private val shopee = ConnectionEntity(tenantId = tenant, externalId = "shopee", status = "ativa", sellerExternalId = "m:5:44120", stockSyncSince = since)
    private val olx = ConnectionEntity(tenantId = tenant, externalId = "olx", status = "ativa", sellerExternalId = "loja@example.com")
    private val service = SaleWebhookService(connections, events, oauth, properties, leaser, ShopeeApi(http, properties), http, sync, SyncTaskExecutor())
    private val controller = MarketplaceWebhookController(service)

    @BeforeEach
    fun setUp() {
        val all = listOf(ml, shopee, olx)
        properties.mercadoLivre.webhookSecret = secret
        Mockito.`when`(connections.findByExternalIdAndStatus("ml", "ativa")).thenReturn(listOf(ml, ConnectionEntity(externalId = "ml", sellerExternalId = "1")))
        Mockito.`when`(connections.findByExternalIdAndStatus("shopee", "ativa")).thenReturn(listOf(shopee))
        Mockito.`when`(connections.findByTenantIdAndExternalId(tenant, "olx")).thenReturn(olx)
        Mockito.`when`(connections.findById(anyOf(UUID::class.java, tenant))).thenAnswer { call ->
            Optional.ofNullable(all.find { it.id == call.getArgument<UUID>(0) })
        }
        Mockito.`when`(leaser.lease(anyOf(UUID::class.java, tenant))).thenAnswer { call ->
            val connection = all.first { it.id == call.getArgument<UUID>(0) }
            ConnectionAccess(connection.externalId, "tok", connection.sellerExternalId.orEmpty())
        }
        Mockito.`when`(events.saveAndFlush(anyOf(RawEventEntity::class.java, RawEventEntity()))).thenAnswer { call ->
            call.getArgument<RawEventEntity>(0).also(stored::add)
        }
        Mockito.`when`(events.findById(anyOf(UUID::class.java, tenant))).thenAnswer { call ->
            Optional.ofNullable(stored.find { it.id == call.getArgument<UUID>(0) })
        }
        Mockito.`when`(events.existsByTenantIdAndIdempotencyKey(anyOf(UUID::class.java, tenant), text())).thenAnswer { call ->
            stored.any { it.idempotencyKey == call.getArgument<String>(1) }
        }
        Mockito.doAnswer { call ->
            processed.add(call.getArgument(0))
            settles
        }.`when`(sync).process(anyOf(SaleEvent::class.java, SaleEvent(tenant, "", "", since, false, emptyList())))
    }

    /** The secret travels in the URL for Mercado Livre and in the Authorization header for the others. */
    private fun post(provider: String, credential: String?, body: ByteArray?) = controller.receive(
        provider,
        credential.takeIf { provider.equals("ml", ignoreCase = true) },
        credential.takeUnless { provider.equals("ml", ignoreCase = true) },
        MockHttpServletRequest().apply { body?.let(::setContent) },
    )

    private fun mlPush(resource: String = "/orders/2000001", topic: String = "orders_v2", app: Long = 777, user: Long = 99, attempts: Int = 1) =
        """{"resource":"$resource","user_id":$user,"topic":"$topic","application_id":$app,"attempts":$attempts}""".toByteArray()

    private fun mlOrder(status: String, created: String = "2026-11-10T09:00:00.000-03:00", seller: Int = 99) = mapOf(
        "id" to 2000001,
        "status" to status,
        "date_created" to created,
        "seller" to mapOf("id" to seller),
        "order_items" to listOf(
            mapOf("quantity" to 2, "item" to mapOf("id" to "MLB1", "title" to "Camisa", "seller_sku" to "ABC")),
            mapOf("quantity" to 1, "item" to mapOf("id" to "MLB2", "title" to "Boné", "seller_custom_field" to "CAP")),
            mapOf("quantity" to 1, "item" to mapOf("id" to "MLB3")),
        ),
    )

    @Test
    fun `mercado livre paid order becomes a sale and is answered at once`() {
        http.on("/orders/2000001", body = mlOrder("paid"))

        assertEquals(200, post("ML", secret, mlPush()).statusCode.value())
        val event = processed.single()
        assertEquals(listOf("ml", "2000001", false), listOf(event.provider, event.orderId, event.cancelled))
        assertEquals(listOf(SaleLine("ABC", 2, "Camisa"), SaleLine("CAP", 1, "Boné"), SaleLine("", 1, "")), event.lines)
        assertEquals("webhook.done", stored.single().eventType)

        post("ml", secret, mlPush())
        assertEquals(1, stored.size, "an identical body is stored once")
    }

    @Test
    fun `mercado livre pushes are filtered before anything is stored`() {
        assertEquals(401, post("ml", secret, mlPush(app = 1)).statusCode.value())
        assertEquals(401, post("ml", secret, "not json".toByteArray()).statusCode.value())
        assertEquals(401, post("ml", secret, null).statusCode.value())
        assertEquals(401, post("amazon", null, mlPush()).statusCode.value())
        assertEquals(413, post("ml", secret, ByteArray(70_000)).statusCode.value())
        assertEquals(401, post("ml", null, mlPush()).statusCode.value())
        assertEquals(401, post("ml", "wrong", mlPush()).statusCode.value())
        assertEquals(200, post("ml", secret, mlPush(topic = "items")).statusCode.value())
        assertEquals(200, post("ml", secret, mlPush(resource = "/orders/../users/me")).statusCode.value())
        assertEquals(200, post("ml", secret, mlPush(user = 12345)).statusCode.value())
        assertEquals(200, post("ml", secret, """{"application_id":777,"topic":"orders_v2","resource":"/orders/1"}""".toByteArray()).statusCode.value())
        assertTrue(stored.isEmpty())
        assertTrue(http.calls.isEmpty())

        assertFalse(service.receive("ml", secret, ByteArray(70_000)))
        properties.mercadoLivre.clientId = ""
        assertFalse(service.receive("ml", secret, mlPush(app = 0)))
        properties.mercadoLivre.clientId = "777"
        properties.mercadoLivre.webhookSecret = ""
        assertFalse(service.receive("ml", "", mlPush()), "no configured secret refuses every push")
    }

    @Test
    fun `mercado livre ignores other statuses, other sellers and orders older than the connection`() {
        http.on("/orders/2000001", body = mlOrder("payment_required"))
        service.receive("ml", secret, mlPush(attempts = 1))
        http.calls.clear()

        val cancelled = FakeHttp().on("/orders/2000001", body = mlOrder("cancelled"))
        val foreign = FakeHttp().on("/orders/2000001", body = mlOrder("paid", seller = 5))
        val old = FakeHttp().on("/orders/2000001", body = mlOrder("paid", created = "2026-10-01T09:00:00.000-03:00"))
        listOf(cancelled, foreign, old).forEachIndexed { index, fake ->
            SaleWebhookService(connections, events, oauth, properties, leaser, ShopeeApi(fake, properties), fake, sync, SyncTaskExecutor())
                .receive("ml", secret, mlPush(attempts = index + 2))
        }

        assertEquals(listOf(true), processed.map { it.cancelled })
        assertEquals(List(4) { "webhook.done" }, stored.map { it.eventType })
    }

    @Test
    fun `a failing event ends as failed and pending events are replayed once`() {
        service.receive("ml", secret, mlPush())
        assertEquals("webhook.failed", stored.single().eventType)
        assertEquals(3, http.sent("/orders/2000001").size, "the marketplace will not resend, so the call is retried")

        val pending = RawEventEntity(tenantId = tenant, connectionId = ml.id, source = "ml", eventType = "webhook.pending", payload = String(mlPush(attempts = 9)))
        val orphan = RawEventEntity(tenantId = tenant, connectionId = null, source = "ml", eventType = "webhook.pending", payload = "{}")
        stored.addAll(listOf(pending, orphan))
        Mockito.`when`(events.findByEventType("webhook.pending")).thenReturn(listOf(pending, orphan))
        http.on("/orders/2000001", body = mlOrder("paid"))
        service.replayPending()

        assertEquals(listOf("webhook.failed", "webhook.done", "webhook.done"), stored.map { it.eventType })
        assertEquals(1, processed.size)
        service.handle(pending.id)
        service.handle(UUID.randomUUID())
        assertEquals(1, processed.size)

        Mockito.`when`(events.saveAndFlush(anyOf(RawEventEntity::class.java, RawEventEntity()))).thenThrow(DataIntegrityViolationException("dup"))
        assertTrue(service.receive("ml", secret, mlPush(attempts = 10)))
        assertEquals(1, processed.size)
    }

    @Test
    fun `stock that could not be changed is retried and the event fails after the last attempt`() {
        http.on("/orders/2000001", body = mlOrder("paid"))
        settles = false
        service.receive("ml", secret, mlPush())
        assertEquals(3, processed.size)
        assertEquals("webhook.failed", stored.single().eventType)
    }

    private fun shopeePush(code: Int = 3, shop: Long = 44120, sn: String = "SN1") =
        """{"data":{"ordersn":"$sn","status":"READY_TO_SHIP"},"shop_id":$shop,"code":$code,"timestamp":1}"""

    private fun signed(body: String) = hmacHex("partner-key", "${properties.shopee.webhookUrl}|$body")

    private fun shopeeOrder(status: String) = mapOf(
        "response" to mapOf(
            "order_list" to listOf(
                mapOf(
                    "order_sn" to "SN1",
                    "order_status" to status,
                    "create_time" to 1_794_300_000,
                    "item_list" to listOf(
                        mapOf("item_name" to "Camisa", "item_sku" to "PAI", "model_sku" to "ABC", "model_quantity_purchased" to 2),
                        mapOf("item_name" to "Boné", "item_sku" to "CAP", "model_sku" to "", "model_quantity_purchased" to 1),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `shopee push needs a valid signature and reads the order lines`() {
        http.on("get_order_detail", body = shopeeOrder("READY_TO_SHIP"))
        val body = shopeePush()

        assertEquals(401, post("shopee", "bad", body.toByteArray()).statusCode.value())
        assertEquals(401, post("shopee", null, body.toByteArray()).statusCode.value())
        assertEquals(200, post("shopee", signed(body), body.toByteArray()).statusCode.value())

        val event = processed.single()
        assertEquals(listOf("shopee", "SN1", false), listOf(event.provider, event.orderId, event.cancelled))
        assertEquals(listOf(SaleLine("ABC", 2, "Camisa"), SaleLine("CAP", 1, "Boné")), event.lines)
        assertTrue(http.sent("get_order_detail").single().url.contains("order_sn_list=SN1"))

        val other = shopeePush(code = 4)
        val stranger = shopeePush(shop = 1)
        val noShop = """{"code":3}"""
        listOf(other, stranger, noShop).forEach { assertTrue(service.receive("shopee", signed(it), it.toByteArray())) }
        assertEquals(1, stored.size)
    }

    @Test
    fun `shopee cancelled order gives the stock back and empty answers are dropped`() {
        val cancelled = FakeHttp().on("get_order_detail", body = shopeeOrder("CANCELLED"))
        val body = shopeePush()
        SaleWebhookService(connections, events, oauth, properties, leaser, ShopeeApi(cancelled, properties), cancelled, sync, SyncTaskExecutor())
            .receive("shopee", signed(body), body.toByteArray())
        assertTrue(processed.single().cancelled)

        http.on("get_order_detail", body = mapOf("response" to mapOf("order_list" to emptyList<Any>())))
        val missing = shopeePush(sn = "SN2")
        val noSn = """{"data":{},"shop_id":44120,"code":3}"""
        listOf(missing, noSn).forEach { service.receive("shopee", signed(it), it.toByteArray()) }
        assertEquals(1, processed.size)
        assertEquals(List(3) { "webhook.done" }, stored.map { it.eventType })
    }

    @Test
    fun `olx sold ad is a one unit sale authenticated by the tenant token`() {
        val token = oauth.webhookToken(tenant)
        fun push(status: String, id: String? = "ABC", topic: String = "AD_STATUS") =
            """{"id":"n-$status-$id","topic":"$topic","data":{"ad":{${id?.let { "\"id\":\"$it\"," }.orEmpty()}"status":"$status"}}}""".toByteArray()

        assertEquals(401, post("olx", null, push("sold")).statusCode.value())
        assertEquals(401, post("olx", "$tenant.forged", push("sold")).statusCode.value())
        assertEquals(401, post("olx", "not-a-uuid.x", push("sold")).statusCode.value())
        assertEquals(200, post("olx", "Bearer $token", push("sold")).statusCode.value())
        assertEquals(200, post("olx", token, push("deleted_sold")).statusCode.value())
        service.receive("olx", token, push("published"))
        service.receive("olx", token, push("sold", id = null))
        service.receive("olx", token, push("sold", topic = "LEAD"))

        assertEquals(listOf("ad:ABC", "ad:ABC"), processed.map { it.orderId })
        assertEquals(listOf(SaleLine("ABC", 1, "ABC")), processed.first().lines)
        assertEquals(4, stored.size)

        olx.status = "disponivel"
        service.receive("olx", token, push("sold", id = "OTHER"))
        assertEquals(4, stored.size)
    }

    @Test
    fun `a connection disconnected after the push is skipped`() {
        http.on("/orders/2000001", body = mlOrder("paid"))
        val pending = RawEventEntity(tenantId = tenant, connectionId = ml.id, source = "ml", eventType = "webhook.pending", payload = String(mlPush()))
        stored.add(pending)
        ml.status = "disponivel"
        service.handle(pending.id)
        assertEquals("webhook.done", pending.eventType)
        assertTrue(processed.isEmpty())
    }
}
