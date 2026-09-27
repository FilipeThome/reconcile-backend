package br.com.reconcile.shared.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Locale

private val PORTUGUESE: Locale = Locale.forLanguageTag("pt-BR")
private val ENGLISH: Locale = Locale.forLanguageTag("en-US")

fun resolveApiLocale(header: String?): Locale {
    val token = header
        ?.split(',')
        ?.firstOrNull()
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return if (token == "en" || token.startsWith("en-")) ENGLISH else PORTUGUESE
}

fun apiMessageCatalogsMatch() = PORTUGUESE_MESSAGES.keys == ENGLISH_MESSAGES.keys

fun apiMessage(key: String, vararg args: Any?): String {
    val language = LocaleContextHolder.getLocaleContext()?.locale?.language
    val catalog = if (language == "en") ENGLISH_MESSAGES else PORTUGUESE_MESSAGES
    val template = catalog[key] ?: PORTUGUESE_MESSAGES[key] ?: key
    return args.foldIndexed(template) { index, text, arg -> text.replace("{$index}", arg?.toString() ?: "") }
}

fun apiErrorBody(code: String, message: String): String {
    val escaped = message
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    return """{"code":"$code","message":"$escaped"}"""
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiLocaleFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        LocaleContextHolder.setLocale(resolveApiLocale(request.getHeader("Accept-Language")))
        try {
            filterChain.doFilter(request, response)
        } finally {
            LocaleContextHolder.resetLocaleContext()
        }
    }
}

private val PORTUGUESE_MESSAGES = mapOf(
    "auth.required" to "Autenticação necessária",
    "auth.denied" to "Acesso negado",
    "auth.invalidRequest" to "Requisição inválida",
    "auth.method" to "Método não suportado",
    "auth.missingUser" to "Usuário autenticado ausente",
    "auth.internal" to "Falha interna",
    "auth.badCredentials" to "Credenciais inválidas",
    "auth.tokenMissing" to "Token ausente",
    "auth.tokenInvalid" to "Token inválido ou expirado",
    "auth.sessionInvalid" to "Sessão inválida",
    "auth.tenantMismatch" to "Tenant do header não confere com o token",
    "auth.rateLimit" to "Muitas tentativas. Aguarde um minuto.",
    "auth.httpMethod" to "Método HTTP não suportado",
    "oauth.provider" to "Provedor OAuth {0} não suportado",
    "oauth.stateMismatch" to "OAuth state não corresponde ao provedor {0}",
    "oauth.codeMissing" to "Código de autorização ausente",
    "oauth.shopeeIds" to "Shopee não devolveu shop_id nem main_account_id",
    "oauth.shopeeShop" to "Shopee não devolveu shop_id. Reconecte autorizando uma loja.",
    "oauth.amazonSeller" to "selling_partner_id da Amazon é inválido",
    "oauth.shopeeMerchant" to "Merchant da Shopee ausente. Reconecte a integração.",
    "oauth.shopeeShopMissing" to "Shop da Shopee ausente. Reconecte a integração.",
    "oauth.session" to "Sessão OAuth expirada ou inválida",
    "oauth.credentials" to "Credenciais OAuth ausentes",
    "oauth.notOauth" to "Integração {0} não usa OAuth",
    "oauth.state" to "OAuth state inválido",
    "oauth.exchange" to "Falha ao trocar o código {0}",
    "oauth.hint.ml" to "Defina MERCADO_LIVRE_CLIENT_ID, MERCADO_LIVRE_CLIENT_SECRET e a redirect URI no .env",
    "oauth.hint.amazon" to "Defina AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET e a redirect URI no .env",
    "oauth.hint.shopee" to "Defina SHOPEE_PARTNER_ID, SHOPEE_PARTNER_KEY e a redirect URI no .env",
    "stock.integrationMissing" to "Integração Mercado Livre não encontrada",
    "stock.connect" to "Conecte o Mercado Livre antes de consultar o estoque.",
    "stock.token" to "Token OAuth ausente. Reconecte o Mercado Livre.",
    "stock.status" to "Status de anúncio inválido.",
    "stock.sellerUnknown" to "Não foi possível identificar o seller do Mercado Livre.",
    "stock.sellerMismatch" to "O seller salvo não corresponde à conta conectada. Reconecte o Mercado Livre.",
    "stock.quantity" to "A quantidade deve ficar entre 0 e 99999.",
    "stock.warehouseInvalid" to "Depósito inválido.",
    "stock.variationInvalid" to "Variação inválida.",
    "stock.unreadable" to "Não foi possível ler o estoque deste anúncio no Mercado Livre.",
    "stock.full" to "Estoque Full (Mercado Livre) não pode ser alterado por esta API.",
    "stock.variationRequired" to "Informe a variação para atualizar o estoque.",
    "stock.variationMissing" to "Variação não encontrada neste anúncio.",
    "stock.itemWithId" to "Anúncio {0} não encontrado.",
    "stock.itemOtherAccount" to "Anúncio não encontrado na conta conectada.",
    "stock.userProductInvalid" to "user_product_id inválido.",
    "stock.manyWarehouses" to "Há mais de um depósito. Informe qual estoque atualizar.",
    "stock.warehouseMissing" to "Depósito não encontrado neste anúncio.",
    "stock.warehouseNode" to "Há mais de um depósito com esse identificador. Informe o nó do depósito.",
    "stock.warehouseNoId" to "Depósito sem identificador para atualizar o estoque.",
    "stock.warehouseQty" to "A API do Mercado Livre não informou a quantidade de um depósito.",
    "stock.version" to "A API do Mercado Livre não informou a versão do estoque.",
    "stock.badUrl" to "URL do Mercado Livre inválida.",
    "stock.noUserProduct" to "Anúncio sem user product para atualizar este estoque.",
    "stock.itemMissing" to "Anúncio não encontrado.",
    "stock.remote" to "A API do Mercado Livre recusou a operação ({0}): {1}",
    "stock.remoteUnknown" to "erro desconhecido",
    "stock.sellerInvalid" to "Seller do Mercado Livre inválido. Reconecte a integração.",
    "stock.itemId" to "Identificador de anúncio inválido.",
    "stock.apiMissing" to "API do Mercado Livre não configurada.",
    "stock.locked" to "Anúncios com status {0} não aceitam atualização de estoque.",
    "stock.readonly" to "Este anúncio não aceita atualização de estoque.",
    "date.invalid" to "Data inválida: {0}",
    "comment.invalid" to "Comentário inválido",
    "case.status" to "Status de caso inválido: {0}",
    "case.missing" to "Caso {0} não encontrado",
    "rule.missing" to "Regra {0} não encontrada",
    "snapshot.missing" to "Snapshot {0} não encontrado",
    "order.missing" to "Pedido {0} não encontrado",
    "integration.missing" to "Integração {0} não encontrada",
    "closing.missing" to "Fechamento {0} não encontrado",
    "report.type" to "Tipo de relatório inválido",
    "case.transition" to "Transição de caso inválida: {0} -> {1}",
    "closing.openOnly" to "Somente períodos OPEN podem ser fechados",
    "closing.delta" to "Delta auditável só se aplica após CLOSED",
    "transition.invalid" to "Transição inválida",
    "ingest.connect" to "Conecte {0} antes do backfill",
    "ingest.token" to "Token OAuth ausente. Reconecte {0}.",
    "ingest.refused" to "A API do marketplace recusou a consulta ({0})",
    "ingest.unsupported" to "Ingestão não suportada para {0}",
    "shopee.env" to "Defina SHOPEE_PARTNER_ID e SHOPEE_PARTNER_KEY no .env",
    "shopee.list" to "Falha ao listar pedidos Shopee",
    "amazon.client" to "Defina AMAZON_CLIENT_ID e AMAZON_CLIENT_SECRET no .env",
    "amazon.aws" to "Defina AMAZON_AWS_ACCESS_KEY e AMAZON_AWS_SECRET_KEY no .env",
    "ml.seller" to "Seller do Mercado Livre ausente. Reconecte a integração.",
)

private val ENGLISH_MESSAGES = mapOf(
    "auth.required" to "Authentication required",
    "auth.denied" to "Access denied",
    "auth.invalidRequest" to "Invalid request",
    "auth.method" to "Method not supported",
    "auth.missingUser" to "Authenticated user is missing",
    "auth.internal" to "Internal failure",
    "auth.badCredentials" to "Invalid credentials",
    "auth.tokenMissing" to "Missing token",
    "auth.tokenInvalid" to "Invalid or expired token",
    "auth.sessionInvalid" to "Invalid session",
    "auth.tenantMismatch" to "Tenant header does not match the token",
    "auth.rateLimit" to "Too many attempts. Wait one minute.",
    "auth.httpMethod" to "HTTP method not supported",
    "oauth.provider" to "OAuth provider {0} is not supported",
    "oauth.stateMismatch" to "OAuth state does not match provider {0}",
    "oauth.codeMissing" to "Authorization code is missing",
    "oauth.shopeeIds" to "Shopee did not return a shop_id or main_account_id",
    "oauth.shopeeShop" to "Shopee did not return a shop_id. Reconnect and authorize a shop.",
    "oauth.amazonSeller" to "Amazon selling_partner_id is invalid",
    "oauth.shopeeMerchant" to "Shopee merchant is missing. Reconnect the integration.",
    "oauth.shopeeShopMissing" to "Shopee shop is missing. Reconnect the integration.",
    "oauth.session" to "OAuth session expired or is invalid",
    "oauth.credentials" to "OAuth credentials are missing",
    "oauth.notOauth" to "Integration {0} does not use OAuth",
    "oauth.state" to "Invalid OAuth state",
    "oauth.exchange" to "Could not exchange the {0} code",
    "oauth.hint.ml" to "Set MERCADO_LIVRE_CLIENT_ID, MERCADO_LIVRE_CLIENT_SECRET, and the redirect URI in .env",
    "oauth.hint.amazon" to "Set AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET, and the redirect URI in .env",
    "oauth.hint.shopee" to "Set SHOPEE_PARTNER_ID, SHOPEE_PARTNER_KEY, and the redirect URI in .env",
    "stock.integrationMissing" to "Mercado Livre integration was not found",
    "stock.connect" to "Connect Mercado Livre before reading stock.",
    "stock.token" to "OAuth token is missing. Reconnect Mercado Livre.",
    "stock.status" to "Invalid listing status.",
    "stock.sellerUnknown" to "Could not identify the Mercado Livre seller.",
    "stock.sellerMismatch" to "The saved seller does not match the connected account. Reconnect Mercado Livre.",
    "stock.quantity" to "Quantity must be between 0 and 99999.",
    "stock.warehouseInvalid" to "Invalid warehouse.",
    "stock.variationInvalid" to "Invalid variation.",
    "stock.unreadable" to "Could not read stock for this Mercado Livre listing.",
    "stock.full" to "Mercado Livre Full stock cannot be changed through this API.",
    "stock.variationRequired" to "Provide the variation to update stock.",
    "stock.variationMissing" to "Variation was not found on this listing.",
    "stock.itemWithId" to "Listing {0} was not found.",
    "stock.itemOtherAccount" to "Listing was not found on the connected account.",
    "stock.userProductInvalid" to "Invalid user_product_id.",
    "stock.manyWarehouses" to "There is more than one warehouse. Specify which stock to update.",
    "stock.warehouseMissing" to "Warehouse was not found on this listing.",
    "stock.warehouseNode" to "More than one warehouse has that identifier. Provide the warehouse node.",
    "stock.warehouseNoId" to "Warehouse has no identifier to update stock.",
    "stock.warehouseQty" to "The Mercado Livre API did not report a warehouse quantity.",
    "stock.version" to "The Mercado Livre API did not report the stock version.",
    "stock.badUrl" to "Invalid Mercado Livre URL.",
    "stock.noUserProduct" to "Listing has no user product to update this stock.",
    "stock.itemMissing" to "Listing was not found.",
    "stock.remote" to "The Mercado Livre API rejected the operation ({0}): {1}",
    "stock.remoteUnknown" to "unknown error",
    "stock.sellerInvalid" to "Invalid Mercado Livre seller. Reconnect the integration.",
    "stock.itemId" to "Invalid listing identifier.",
    "stock.apiMissing" to "Mercado Livre API is not configured.",
    "stock.locked" to "Listings with status {0} cannot update stock.",
    "stock.readonly" to "This listing does not accept stock updates.",
    "date.invalid" to "Invalid date: {0}",
    "comment.invalid" to "Invalid comment",
    "case.status" to "Invalid case status: {0}",
    "case.missing" to "Case {0} was not found",
    "rule.missing" to "Rule {0} was not found",
    "snapshot.missing" to "Snapshot {0} was not found",
    "order.missing" to "Order {0} was not found",
    "integration.missing" to "Integration {0} was not found",
    "closing.missing" to "Closing {0} was not found",
    "report.type" to "Invalid report type",
    "case.transition" to "Invalid case transition: {0} -> {1}",
    "closing.openOnly" to "Only OPEN periods can be closed",
    "closing.delta" to "An auditable delta applies only after CLOSED",
    "transition.invalid" to "Invalid transition",
    "ingest.connect" to "Connect {0} before backfill",
    "ingest.token" to "OAuth token is missing. Reconnect {0}.",
    "ingest.refused" to "The marketplace API rejected the query ({0})",
    "ingest.unsupported" to "Ingestion is not supported for {0}",
    "shopee.env" to "Set SHOPEE_PARTNER_ID and SHOPEE_PARTNER_KEY in .env",
    "shopee.list" to "Could not list Shopee orders",
    "amazon.client" to "Set AMAZON_CLIENT_ID and AMAZON_CLIENT_SECRET in .env",
    "amazon.aws" to "Set AMAZON_AWS_ACCESS_KEY and AMAZON_AWS_SECRET_KEY in .env",
    "ml.seller" to "Mercado Livre seller is missing. Reconnect the integration.",
)
