package br.com.reconcile.integration

import br.com.reconcile.TestAuth
import br.com.reconcile.integration.api.MarketplaceOAuthController
import br.com.reconcile.integration.application.IntegrationService
import br.com.reconcile.integration.oauth.JsonHttpClient
import br.com.reconcile.integration.oauth.MarketplaceOAuthProperties
import br.com.reconcile.integration.oauth.MarketplaceOAuthService
import br.com.reconcile.integration.oauth.OAuthTokens
import br.com.reconcile.integration.oauth.RestJsonHttpClient
import br.com.reconcile.shared.api.UnprocessableException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketplaceOAuthTest {
    private val http = Mockito.mock(JsonHttpClient::class.java)
    private val properties = MarketplaceOAuthProperties().apply {
        frontendRedirect = "http://localhost:5173/integracoes"
        mercadoLivre.apply {
            clientId = "1620218256833906"
            clientSecret = "ml-secret"
            redirectUri = "http://localhost:8080/api/v1/integrations/oauth/ml/callback"
            authorizeUrl = "https://auth.mercadolivre.com.br/authorization"
            tokenUrl = "https://api.mercadolibre.com/oauth/token"
            apiUrl = "https://api.mercadolibre.com"
            pkce = true
        }
        shopee.apply {
            clientId = "10090"
            clientSecret = "shopee-partner-key"
            redirectUri = "http://localhost:8080/api/v1/integrations/oauth/shopee/callback"
            authorizeUrl = "https://open.shopee.com.br/auth"
            tokenUrl = "https://openplatform.shopee.com.br/api/v2/auth/token/get"
            apiUrl = "https://openplatform.shopee.com.br"
        }
    }
    private val oauth = MarketplaceOAuthService(properties, http, TestAuth.JWT_SECRET)

    @AfterEach
    fun cleanup() {
        TestAuth.logout()
    }

    @Test
    fun `builds official brazil authorization pages`() {
        val ml = oauth.authorizationUrl("ml", TestAuth.tenantId, TestAuth.userId).authorizationUrl
        assertTrue(ml.startsWith("https://auth.mercadolivre.com.br/authorization?"))
        assertTrue(ml.contains("response_type=code"))
        assertTrue(ml.contains("client_id=1620218256833906"))
        assertTrue(ml.contains("code_challenge_method=S256"))

        val shopee = oauth.authorizationUrl("shopee", TestAuth.tenantId, TestAuth.userId).authorizationUrl
        assertTrue(shopee.startsWith("https://open.shopee.com.br/auth?"))
        assertTrue(shopee.contains("partner_id=10090"))
        assertTrue(shopee.contains("auth_type=seller"))
        assertTrue(shopee.contains("response_type=code"))

        properties.amazon.apply {
            clientId = "amzn1.application.demo"
            clientSecret = "amz-secret"
            redirectUri = "http://localhost:8080/api/v1/integrations/oauth/amazon/callback"
            authorizeUrl = "https://sellercentral.amazon.com.br/apps/authorize/consent"
            tokenUrl = "https://api.amazon.com/auth/o2/token"
            apiUrl = "https://sellingpartnerapi-na.amazon.com"
            pkce = false
            awsAccessKey = "AKIADEMO"
            awsSecretKey = "aws-secret"
            awsRegion = "us-east-1"
            marketplaceId = "A2Q3Y263D00KWC"
        }
        val amazon = oauth.authorizationUrl("amazon", TestAuth.tenantId, TestAuth.userId).authorizationUrl
        assertTrue(amazon.startsWith("https://sellercentral.amazon.com.br/apps/authorize/consent?"))
        assertTrue(amazon.contains("application_id=amzn1.application.demo"))
        assertTrue(amazon.contains("redirect_uri="))
    }

    @Test
    fun `rejects missing credentials and invalid callbacks`() {
        properties.mercadoLivre.clientId = ""
        properties.shopee.clientSecret = ""
        val empty = MarketplaceOAuthService(properties, http, TestAuth.JWT_SECRET)
        assertThrows<UnprocessableException> { empty.authorizationUrl("ml", TestAuth.tenantId, TestAuth.userId) }
        assertTrue(empty.configuredHint("ml")!!.contains("MERCADO_LIVRE_CLIENT_ID"))
        assertTrue(empty.configuredHint("shopee")!!.contains("SHOPEE_PARTNER_ID"))
        properties.mercadoLivre.clientId = "1620218256833906"
        properties.shopee.clientSecret = "shopee-partner-key"

        assertThrows<UnprocessableException> { oauth.authorizationUrl("csv", TestAuth.tenantId, TestAuth.userId) }
        assertThrows<UnprocessableException> { oauth.exchange("ml", "code", "bad", null, null) }
        assertThrows<UnprocessableException> { oauth.exchange("other", "code", "x.y", null, null) }
        assertEquals(null, oauth.configuredHint("csv"))
        assertEquals("http://localhost:5173/integracoes?connected=ml", oauth.frontendRedirect(connected = "ml"))
        assertEquals("http://localhost:5173/integracoes?oauth_error=exchange_failed", oauth.frontendRedirect(error = "falhou"))
        assertEquals("http://localhost:5173/integracoes", oauth.frontendRedirect(connected = "<script>alert(1)</script>"))
        assertEquals(
            "http://localhost:5173/integracoes?oauth_error=exchange_failed",
            oauth.frontendRedirect(error = "Reconecte em https://evil.example"),
        )
    }

    @Test
    fun `exchanges mercado livre code for token`() {
        val start = oauth.authorizationUrl("ml", TestAuth.tenantId, TestAuth.userId)
        val state = query(start.authorizationUrl, "state")
        Mockito.`when`(http.postForm(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(
            mapOf("access_token" to "APP_USR-1", "refresh_token" to "TG-1", "expires_in" to 21600, "user_id" to 314029626),
        )
        Mockito.`when`(http.getJson("https://api.mercadolibre.com/users/me", "APP_USR-1")).thenReturn(
            mapOf("id" to 314029626, "nickname" to "LOJA", "site_id" to "MLB"),
        )
        val tokens = oauth.exchange("mercadolivre", "AUTH-CODE", state, null, null)
        assertEquals("ml", tokens.provider)
        assertEquals("314029626", tokens.sellerExternalId)
        assertEquals("APP_USR-1", tokens.accessToken)
        assertEquals("OAuth seller 314029626 · LOJA · MLB", tokens.sellerLabel)
        assertThrows<UnprocessableException> { oauth.exchange("ml", "AUTH-CODE", state, null, null) }
    }

    @Test
    fun `exchanges shopee code for token`() {
        val start = oauth.authorizationUrl("shopee", TestAuth.tenantId, TestAuth.userId)
        val state = query(start.authorizationUrl, "state")
        Mockito.`when`(http.postJson(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(
            mapOf(
                "access_token" to "shp-token",
                "refresh_token" to "shp-refresh",
                "expire_in" to 14400,
                "error" to "",
                "shop_id_list" to listOf(88001),
                "merchant_id_list" to listOf(77),
            ),
        )
        val tokens = oauth.exchange("shopee", "CODE", state, 44120, null)
        assertEquals("shopee", tokens.provider)
        assertEquals("44120", tokens.sellerExternalId)

        val startMain = oauth.authorizationUrl("shopee", TestAuth.tenantId, TestAuth.userId)
        val stateMain = query(startMain.authorizationUrl, "state")
        val main = oauth.exchange("shopee", "CODE2", stateMain, null, 99)
        assertEquals("m:77:88001", main.sellerExternalId)

        val startFail = oauth.authorizationUrl("shopee", TestAuth.tenantId, TestAuth.userId)
        val stateFail = query(startFail.authorizationUrl, "state")
        assertThrows<UnprocessableException> { oauth.exchange("shopee", "CODE3", stateFail, null, null) }

        val startErr = oauth.authorizationUrl("shopee", TestAuth.tenantId, TestAuth.userId)
        val stateErr = query(startErr.authorizationUrl, "state")
        Mockito.`when`(http.postJson(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(
            mapOf("error" to "error_auth", "message" to "bad sign"),
        )
        assertThrows<UnprocessableException> { oauth.exchange("shopee", "CODE4", stateErr, 1, null) }
    }

    @Test
    fun `exchanges and refreshes amazon tokens and rejects a bad seller id`() {
        properties.amazon.apply {
            clientId = "amzn1.application.demo"
            clientSecret = "amz-secret"
            redirectUri = "http://localhost:8080/api/v1/integrations/oauth/amazon/callback"
            authorizeUrl = "https://sellercentral.amazon.com.br/apps/authorize/consent"
            tokenUrl = "https://api.amazon.com/auth/o2/token"
            apiUrl = "https://sellingpartnerapi-na.amazon.com"
            pkce = false
        }
        val start = oauth.authorizationUrl("amazon", TestAuth.tenantId, TestAuth.userId)
        val state = query(start.authorizationUrl, "state")
        assertThrows<UnprocessableException> { oauth.exchange("amazon", "CODE", state, null, null, "../evil") }
        assertThrows<UnprocessableException> { oauth.exchange("amazon", "CODE", state, null, null, "seller id") }

        val again = oauth.authorizationUrl("amazon", TestAuth.tenantId, TestAuth.userId)
        val stateOk = query(again.authorizationUrl, "state")
        Mockito.`when`(http.postForm(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(
            mapOf("access_token" to "Atza|token", "refresh_token" to "Atzr|refresh", "expires_in" to 3600),
        )
        val tokens = oauth.exchange("amazon", "CODE", stateOk, null, null, "A1AMZDEMO204918")
        assertEquals("amazon", tokens.provider)
        assertEquals("A1AMZDEMO204918", tokens.sellerExternalId)
        assertEquals("Atza|token", tokens.accessToken)

        val lease = oauth.ensureAccessToken("amazon", "old", "Atzr|refresh", Instant.now().minusSeconds(30), "A1AMZDEMO204918")
        assertTrue(lease.rotated)
        assertEquals("Atza|token", lease.accessToken)
    }

    @Test
    fun `controller redirects success and errors`() {
        val service = Mockito.mock(MarketplaceOAuthService::class.java)
        val integrations = Mockito.mock(IntegrationService::class.java)
        val controller = MarketplaceOAuthController(service, integrations)
        Mockito.`when`(service.frontendRedirect(error = "access_denied")).thenReturn("http://localhost:5173/integracoes?oauth_error=access_denied")
        assertTrue(controller.callback("ml", null, null, null, null, "access_denied", null).url!!.contains("oauth_error"))
        Mockito.`when`(service.frontendRedirect(error = "exchange_failed")).thenReturn("http://localhost:5173/integracoes?oauth_error=exchange_failed")
        assertTrue(controller.callback("ml", null, null, null, null, null, "blocked").url!!.contains("oauth_error=exchange_failed"))

        val tokens = OAuthTokens("ml", TestAuth.tenantId, "1", "tok", null, Instant.now())
        Mockito.`when`(service.exchange("ml", "c", "s", null, null)).thenReturn(tokens)
        Mockito.`when`(service.frontendRedirect(connected = "ml")).thenReturn("http://localhost:5173/integracoes?connected=ml")
        assertTrue(controller.callback("ml", "c", "s", null, null, null, null).url!!.contains("connected=ml"))

        Mockito.doThrow(UnprocessableException("bad")).`when`(service).exchange("ml", "c", "s", null, null)
        assertTrue(controller.callback("ml", "c", "s", null, null, null, null).url!!.contains("oauth_error"))

        Mockito.doThrow(IllegalStateException("boom")).`when`(service).exchange("ml", "c", "s", null, null)
        assertTrue(controller.callback("ml", "c", "s", null, null, null, null).url!!.contains("oauth_error"))

        val amazon = OAuthTokens("amazon", TestAuth.tenantId, "A1AMZDEMO204918", "tok", null, Instant.now())
        Mockito.`when`(service.exchange("amazon", "spapi-code", "st", null, null, "A1AMZDEMO204918")).thenReturn(amazon)
        Mockito.`when`(service.frontendRedirect(connected = "amazon")).thenReturn("http://localhost:5173/integracoes?connected=amazon")
        assertTrue(
            controller.callback("amazon", null, "st", null, null, null, null, "A1AMZDEMO204918", "spapi-code")
                .url!!.contains("connected=amazon"),
        )
    }

    @Test
    fun `rest client posts and gets json`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = RestJsonHttpClient(builder)
        server.expect(requestTo("http://localhost/token")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"access_token":"a"}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://localhost/json")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://localhost/me")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"id":7}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("http://localhost/missing")).andExpect(method(HttpMethod.GET))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.NOT_FOUND))
        assertEquals("a", client.postForm("http://localhost/token", mapOf("grant_type" to "code"))["access_token"])
        assertEquals(true, client.postJson("http://localhost/json", mapOf("code" to "x"))["ok"])
        assertEquals(7, (client.getJson("http://localhost/me", "tok")["id"] as Number).toInt())
        assertTrue(client.getJsonOrEmpty("http://localhost/missing", "tok").isEmpty())
        server.verify()
    }

    @Test
    fun `ml token failure and users me fallback`() {
        val start = oauth.authorizationUrl("ml", TestAuth.tenantId, TestAuth.userId)
        val state = query(start.authorizationUrl, "state")
        Mockito.`when`(http.postForm(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(mapOf("error" to "invalid_grant"))
        assertThrows<UnprocessableException> { oauth.exchange("ml", "c", state, null, null) }

        val start2 = oauth.authorizationUrl("ml", TestAuth.tenantId, TestAuth.userId)
        val state2 = query(start2.authorizationUrl, "state")
        Mockito.`when`(http.postForm(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(mapOf("access_token" to "t"))
        Mockito.`when`(http.getJson("https://api.mercadolibre.com/users/me", "t")).thenReturn(emptyMap())
        assertEquals("ml-user", oauth.exchange("ml", "c", state2, null, null).sellerExternalId)

        val start3 = oauth.authorizationUrl("ml", TestAuth.tenantId, TestAuth.userId)
        val state3 = query(start3.authorizationUrl, "state")
        Mockito.`when`(http.postForm(Mockito.anyString() ?: "", Mockito.any() ?: emptyMap())).thenReturn(
            mapOf("access_token" to "t2", "user_id" to 195831534),
        )
        Mockito.`when`(http.getJson("https://api.mercadolibre.com/users/me", "t2")).thenReturn(emptyMap())
        assertEquals("195831534", oauth.exchange("ml", "c", state3, null, null).sellerExternalId)
    }

    private fun query(url: String, name: String) =
        UriComponentsBuilder.fromUriString(url).build().queryParams.getFirst(name)
}
