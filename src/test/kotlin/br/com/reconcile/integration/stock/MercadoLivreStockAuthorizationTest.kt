package br.com.reconcile.integration.stock

import br.com.reconcile.TestAuth
import br.com.reconcile.integration.api.MercadoLivreStockController
import br.com.reconcile.integration.api.UpdateStockRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

@SpringJUnitConfig(MercadoLivreStockAuthorizationTest.Config::class)
class MercadoLivreStockAuthorizationTest {
    @Autowired
    private lateinit var controller: MercadoLivreStockController

    @Autowired
    private lateinit var stock: MercadoLivreStockService

    @AfterEach
    fun tearDown() {
        TestAuth.logout()
    }

    @Test
    fun `contador can read stock and cannot update it`() {
        TestAuth.login("contador")
        val page = StockPage(0, 0, 20, emptyList())
        val item = StockItem("MLB10000001", "Camiseta", null, "active", 1, 0, null, null, true, emptyList(), emptyList())
        Mockito.`when`(stock.list(null, null, 0, 20)).thenReturn(page)
        Mockito.`when`(stock.get("MLB10000001")).thenReturn(item)
        kotlin.test.assertSame(page, controller.list(null, null, 0, 20))
        kotlin.test.assertSame(item, controller.get("MLB10000001"))
        assertThrows<AccessDeniedException> { controller.update("MLB10000001", UpdateStockRequest(1)) }
        Mockito.verify(stock, Mockito.never()).update("MLB10000001", 1, null, null, null)
    }

    @Test
    fun `operador and admin can update stock`() {
        val item = StockItem("MLB10000001", "Camiseta", null, "active", 2, 0, null, null, true, emptyList(), emptyList())
        Mockito.`when`(stock.update("MLB10000001", 2, null, null, null)).thenReturn(item)
        TestAuth.login("operador")
        kotlin.test.assertSame(item, controller.update("MLB10000001", UpdateStockRequest(2)))
        TestAuth.login("admin")
        kotlin.test.assertSame(item, controller.update("MLB10000001", UpdateStockRequest(2)))
    }

    @Test
    fun `anonymous callers cannot read stock`() {
        TestAuth.logout()
        assertThrows<AuthenticationCredentialsNotFoundException> { controller.list(null, null, 0, 20) }
    }

    @Configuration
    @EnableMethodSecurity
    class Config {
        @Bean
        fun stock(): MercadoLivreStockService = Mockito.mock(MercadoLivreStockService::class.java)

        @Bean
        fun controller(stock: MercadoLivreStockService) = MercadoLivreStockController(stock)
    }
}
