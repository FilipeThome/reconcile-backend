package br.com.reconcile.integration.oauth

import br.com.reconcile.shared.api.UnprocessableException
import br.com.reconcile.shared.api.apiMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OAuthStart(val authorizationUrl: String)

data class OAuthTokens(
    val provider: String,
    val tenantId: UUID,
    val sellerExternalId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val sellerLabel: String? = null,
)

data class TokenLease(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val rotated: Boolean,
)

@Service
class MarketplaceOAuthService(
    private val properties: MarketplaceOAuthProperties,
    private val http: JsonHttpClient,
    @Value("\${reconcile.jwt.secret}") private val signingSecret: String,
) {
    private val pending = ConcurrentHashMap<String, PendingAuth>()
    private val random = SecureRandom()

    fun isOAuthProvider(externalId: String) = providerOf(externalId) != null

    fun ensureAccessToken(
        provider: String,
        accessToken: String,
        refreshToken: String?,
        expiresAt: Instant?,
        sellerExternalId: String?,
    ): TokenLease {
        val stillValid = expiresAt == null || expiresAt.isAfter(Instant.now().plusSeconds(90))
        if (stillValid || refreshToken.isNullOrBlank()) {
            return TokenLease(accessToken, refreshToken, expiresAt, rotated = false)
        }
        return when (normalizeProvider(provider)) {
            "ml" -> refreshMercadoLivre(refreshToken)
            "shopee" -> refreshShopee(refreshToken, sellerExternalId)
            "amazon" -> refreshAmazon(refreshToken)
            else -> TokenLease(accessToken, refreshToken, expiresAt, rotated = false)
        }
    }

    fun authorizationUrl(externalId: String, tenantId: UUID, userId: UUID): OAuthStart {
        val provider = requireProvider(externalId)
        val config = configOf(provider)
        requireConfigured(provider, config)
        val nonce = UUID.randomUUID().toString()
        val verifier = if (provider == "ml" && config.pkce) pkceVerifier() else null
        pending[nonce] = PendingAuth(tenantId, userId, provider, verifier, Instant.now().plusSeconds(600))
        val state = sign(nonce)
        val url = when (provider) {
            "ml" -> mercadoLivreAuthorizeUrl(config, state, verifier)
            "shopee" -> shopeeAuthorizeUrl(config, state)
            "amazon" -> amazonAuthorizeUrl(config, state)
            else -> throw UnprocessableException(apiMessage("oauth.provider", provider))
        }
        return OAuthStart(url)
    }

    fun exchange(
        provider: String,
        code: String?,
        state: String?,
        shopId: Long?,
        mainAccountId: Long?,
        sellingPartnerId: String? = null,
    ): OAuthTokens {
        val normalized = normalizeProvider(provider)
        val pendingAuth = consumeState(state)
        if (pendingAuth.provider != normalized) {
            throw UnprocessableException(apiMessage("oauth.stateMismatch", normalized))
        }
        if (code.isNullOrBlank()) {
            throw UnprocessableException(apiMessage("oauth.codeMissing"))
        }
        val config = configOf(normalized)
        return when (normalized) {
            "ml" -> exchangeMercadoLivre(pendingAuth.tenantId, config, code, pendingAuth.verifier)
            "shopee" -> exchangeShopee(pendingAuth.tenantId, config, code, shopId, mainAccountId)
            "amazon" -> exchangeAmazon(pendingAuth.tenantId, config, code, sellingPartnerId)
            else -> throw UnprocessableException(apiMessage("oauth.provider", normalized))
        }
    }

    fun frontendRedirect(connected: String? = null, error: String? = null): String {
        val builder = UriComponentsBuilder.fromUriString(properties.frontendRedirect)
        providerOf(connected ?: "")?.let { builder.queryParam("connected", it) }
        canonicalizeOauthError(error)?.let { builder.queryParam("oauth_error", it) }
        return builder.build().encode().toUriString()
    }

    fun configuredHint(externalId: String): String? {
        val provider = providerOf(externalId) ?: return null
        val config = configOf(provider)
        return if (config.clientId.isBlank() || config.clientSecret.isBlank() || config.redirectUri.isBlank()) {
            when (provider) {
                "ml" -> apiMessage("oauth.hint.ml")
                "amazon" -> apiMessage("oauth.hint.amazon")
                else -> apiMessage("oauth.hint.shopee")
            }
        } else {
            null
        }
    }

    private fun mercadoLivreAuthorizeUrl(config: ProviderProperties, state: String, verifier: String?): String {
        val builder = UriComponentsBuilder.fromUriString(config.authorizeUrl)
            .queryParam("response_type", "code")
            .queryParam("client_id", config.clientId)
            .queryParam("redirect_uri", config.redirectUri)
            .queryParam("state", state)
        if (verifier != null) {
            builder.queryParam("code_challenge", pkceChallenge(verifier))
            builder.queryParam("code_challenge_method", "S256")
        }
        return builder.build().encode().toUriString()
    }

    private fun amazonAuthorizeUrl(config: ProviderProperties, state: String): String =
        UriComponentsBuilder.fromUriString(config.authorizeUrl)
            .queryParam("application_id", config.clientId)
            .queryParam("state", state)
            .queryParam("redirect_uri", config.redirectUri)
            .queryParam("version", "beta")
            .build()
            .encode()
            .toUriString()

    private fun shopeeAuthorizeUrl(config: ProviderProperties, state: String): String =
        UriComponentsBuilder.fromUriString(config.authorizeUrl)
            .queryParam("partner_id", config.clientId)
            .queryParam("auth_type", "seller")
            .queryParam("redirect_uri", config.redirectUri)
            .queryParam("response_type", "code")
            .queryParam("state", state)
            .build()
            .encode()
            .toUriString()

    private fun exchangeMercadoLivre(tenantId: UUID, config: ProviderProperties, code: String, verifier: String?): OAuthTokens {
        val form = mutableMapOf(
            "grant_type" to "authorization_code",
            "client_id" to config.clientId,
            "client_secret" to config.clientSecret,
            "code" to code,
            "redirect_uri" to config.redirectUri,
        )
        if (verifier != null) form["code_verifier"] = verifier
        val token = http.postForm(config.tokenUrl, form)
        val access = stringValue(token, "access_token") ?: throw oauthFailure(token, "Mercado Livre")
        val fromToken = stringValue(token, "user_id")
        val me = fetchMercadoLivreSeller(config, access)
        val seller = me.id ?: fromToken ?: "ml-user"
        return OAuthTokens(
            provider = "ml",
            tenantId = tenantId,
            sellerExternalId = seller,
            accessToken = access,
            refreshToken = stringValue(token, "refresh_token"),
            expiresAt = expiresAt(token["expires_in"]),
            sellerLabel = sellerLabel(seller, me.nickname, me.siteId),
        )
    }

    private fun fetchMercadoLivreSeller(config: ProviderProperties, access: String): MercadoLivreSeller {
        val me = http.getJson("${config.apiUrl.trimEnd('/')}/users/me", access)
        return MercadoLivreSeller(
            id = stringValue(me, "id"),
            nickname = stringValue(me, "nickname"),
            siteId = stringValue(me, "site_id"),
        )
    }

    private fun sellerLabel(id: String, nickname: String?, siteId: String?): String =
        listOfNotNull("OAuth seller $id", nickname, siteId).joinToString(" · ")

    private fun exchangeShopee(
        tenantId: UUID,
        config: ProviderProperties,
        code: String,
        shopId: Long?,
        mainAccountId: Long?,
    ): OAuthTokens {
        val timestamp = Instant.now().epochSecond
        val path = URI.create(config.tokenUrl).path
        val sign = hmacHex(config.clientSecret, "${config.clientId}$path$timestamp")
        val url = UriComponentsBuilder.fromUriString(config.tokenUrl)
            .queryParam("partner_id", config.clientId)
            .queryParam("timestamp", timestamp)
            .queryParam("sign", sign)
            .build()
            .encode()
            .toUriString()
        val body = mutableMapOf<String, Any?>(
            "code" to code,
            "partner_id" to config.clientId.toLongOrNull(),
        )
        when {
            shopId != null -> body["shop_id"] = shopId
            mainAccountId != null -> body["main_account_id"] = mainAccountId
            else -> throw UnprocessableException(apiMessage("oauth.shopeeIds"))
        }
        val token = http.postJson(url, body)
        if (token["error"]?.toString()?.isNotBlank() == true) throw oauthFailure(token, "Shopee")
        val access = stringValue(token, "access_token") ?: throw oauthFailure(token, "Shopee")
        val listedShop = longList(token["shop_id_list"]).firstOrNull()
        val merchantId = longList(token["merchant_id_list"]).firstOrNull()
        val seller = when {
            shopId != null -> shopId.toString()
            listedShop != null && merchantId != null -> "m:$merchantId:$listedShop"
            listedShop != null -> listedShop.toString()
            else -> throw UnprocessableException(apiMessage("oauth.shopeeShop"))
        }
        return OAuthTokens(
            provider = "shopee",
            tenantId = tenantId,
            sellerExternalId = seller,
            accessToken = access,
            refreshToken = stringValue(token, "refresh_token"),
            expiresAt = expiresAt(token["expire_in"] ?: token["expires_in"]),
        )
    }

    private fun exchangeAmazon(
        tenantId: UUID,
        config: ProviderProperties,
        code: String,
        sellingPartnerId: String?,
    ): OAuthTokens {
        val seller = sellingPartnerId?.trim().orEmpty()
        if (!AMAZON_SELLER_ID.matches(seller)) {
            throw UnprocessableException(apiMessage("oauth.amazonSeller"))
        }
        val token = http.postForm(
            config.tokenUrl,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "redirect_uri" to config.redirectUri,
            ),
        )
        val access = stringValue(token, "access_token") ?: throw oauthFailure(token, "Amazon")
        return OAuthTokens(
            provider = "amazon",
            tenantId = tenantId,
            sellerExternalId = seller,
            accessToken = access,
            refreshToken = stringValue(token, "refresh_token"),
            expiresAt = expiresAt(token["expires_in"]),
            sellerLabel = "OAuth seller $seller",
        )
    }

    private fun refreshAmazon(refreshToken: String): TokenLease {
        val config = properties.amazon
        val token = http.postForm(
            config.tokenUrl,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
            ),
        )
        val access = stringValue(token, "access_token") ?: throw oauthFailure(token, "Amazon")
        return TokenLease(
            accessToken = access,
            refreshToken = stringValue(token, "refresh_token") ?: refreshToken,
            expiresAt = expiresAt(token["expires_in"]),
            rotated = true,
        )
    }

    private fun refreshMercadoLivre(refreshToken: String): TokenLease {
        val config = properties.mercadoLivre
        val token = http.postForm(
            config.tokenUrl,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret,
                "refresh_token" to refreshToken,
            ),
        )
        val access = stringValue(token, "access_token") ?: throw oauthFailure(token, "Mercado Livre")
        return TokenLease(
            accessToken = access,
            refreshToken = stringValue(token, "refresh_token") ?: refreshToken,
            expiresAt = expiresAt(token["expires_in"]),
            rotated = true,
        )
    }

    private fun refreshShopee(refreshToken: String, shopId: String?): TokenLease {
        val config = properties.shopee
        val timestamp = Instant.now().epochSecond
        val path = URI.create(config.tokenUrl).path.replace("token/get", "access_token/get")
        val sign = hmacHex(config.clientSecret, "${config.clientId}$path$timestamp")
        val url = UriComponentsBuilder.fromUriString(config.tokenUrl.replace("token/get", "access_token/get"))
            .queryParam("partner_id", config.clientId)
            .queryParam("timestamp", timestamp)
            .queryParam("sign", sign)
            .build()
            .encode()
            .toUriString()
        val body = mutableMapOf<String, Any?>(
            "refresh_token" to refreshToken,
            "partner_id" to config.clientId.toLongOrNull(),
        )
        val raw = shopId?.trim().orEmpty()
        if (raw.startsWith("m:")) {
            val merchant = raw.removePrefix("m:").substringBefore(':').toLongOrNull()
                ?: throw UnprocessableException(apiMessage("oauth.shopeeMerchant"))
            body["merchant_id"] = merchant
        } else {
            val shop = raw.toLongOrNull()
                ?: throw UnprocessableException(apiMessage("oauth.shopeeShopMissing"))
            body["shop_id"] = shop
        }
        val token = http.postJson(url, body)
        if (token["error"]?.toString()?.isNotBlank() == true) throw oauthFailure(token, "Shopee")
        val access = stringValue(token, "access_token") ?: throw oauthFailure(token, "Shopee")
        return TokenLease(
            accessToken = access,
            refreshToken = stringValue(token, "refresh_token") ?: refreshToken,
            expiresAt = expiresAt(token["expire_in"] ?: token["expires_in"]),
            rotated = true,
        )
    }

    private fun consumeState(state: String?): PendingAuth {
        val nonce = verify(state)
        val pendingAuth = pending.remove(nonce) ?: throw UnprocessableException(apiMessage("oauth.session"))
        if (pendingAuth.expiresAt.isBefore(Instant.now())) {
            throw UnprocessableException(apiMessage("oauth.session"))
        }
        return pendingAuth
    }

    private fun requireConfigured(provider: String, config: ProviderProperties) {
        if (config.clientId.isBlank() || config.clientSecret.isBlank() || config.redirectUri.isBlank() ||
            config.authorizeUrl.isBlank() || config.tokenUrl.isBlank()
        ) {
            throw UnprocessableException(configuredHint(provider) ?: apiMessage("oauth.credentials"))
        }
    }

    private fun configOf(provider: String) = when (provider) {
        "ml" -> properties.mercadoLivre
        "shopee" -> properties.shopee
        "amazon" -> properties.amazon
        else -> throw UnprocessableException(apiMessage("oauth.provider", provider))
    }

    private fun requireProvider(externalId: String) =
        providerOf(externalId) ?: throw UnprocessableException(apiMessage("oauth.notOauth", externalId))

    private fun providerOf(externalId: String) = when (externalId.lowercase()) {
        "ml", "mercadolivre", "mercado-livre" -> "ml"
        "shopee" -> "shopee"
        "amazon" -> "amazon"
        else -> null
    }

    private fun canonicalizeOauthError(error: String?): String? {
        if (error.isNullOrBlank()) return null
        return when (error.trim().lowercase()) {
            "access_denied" -> "access_denied"
            "blocked" -> "blocked"
            "invalid_state" -> "invalid_state"
            "missing_code" -> "missing_code"
            "not_configured" -> "not_configured"
            "exchange_failed" -> "exchange_failed"
            else -> "exchange_failed"
        }
    }

    private fun normalizeProvider(provider: String) =
        providerOf(provider) ?: throw UnprocessableException(apiMessage("oauth.provider", provider))

    private fun sign(nonce: String): String = "$nonce.${hmacHex(signingSecret, nonce)}"

    private fun verify(state: String?): String {
        val parts = state?.split('.', limit = 2)
        if (parts == null || parts.size != 2) throw UnprocessableException(apiMessage("oauth.state"))
        val expected = hmacHex(signingSecret, parts[0])
        if (!MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), parts[1].toByteArray(StandardCharsets.UTF_8))) {
            throw UnprocessableException(apiMessage("oauth.state"))
        }
        return parts[0]
    }

    private fun pkceVerifier(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun hmacHex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun stringValue(payload: Map<String, Any?>, key: String) =
        payload[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    private fun longList(raw: Any?): List<Long> = when (raw) {
        is List<*> -> raw.mapNotNull { item ->
            when (item) {
                is Number -> item.toLong()
                is String -> item.toLongOrNull()
                else -> null
            }
        }
        else -> emptyList()
    }

    private fun expiresAt(raw: Any?): Instant? {
        val seconds = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        } ?: return null
        return Instant.now().plusSeconds(seconds)
    }

    private fun oauthFailure(payload: Map<String, Any?>, name: String): UnprocessableException {
        val message = payload["message"]?.toString()
            ?: payload["error_description"]?.toString()
            ?: payload["error"]?.toString()
            ?: apiMessage("oauth.exchange", name)
        return UnprocessableException(message)
    }

    private data class MercadoLivreSeller(
        val id: String?,
        val nickname: String?,
        val siteId: String?,
    )

    companion object {
        val AMAZON_SELLER_ID = Regex("^[A-Za-z0-9]{1,64}$")
    }

    private data class PendingAuth(
        val tenantId: UUID,
        val userId: UUID,
        val provider: String,
        val verifier: String?,
        val expiresAt: Instant,
    )
}
