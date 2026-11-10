package br.com.reconcile.integration.oauth

import br.com.reconcile.shared.api.apiMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

data class HttpJson(
    val status: Int,
    val body: Any?,
    val headers: Map<String, String> = emptyMap(),
)

interface JsonHttpClient {
    fun postForm(url: String, form: Map<String, String>): Map<String, Any?>
    fun postJson(url: String, body: Map<String, Any?>): Map<String, Any?>
    fun getJson(url: String, bearer: String): Map<String, Any?>
    fun getJsonOrEmpty(url: String, bearer: String): Map<String, Any?>
    fun getAmazonJson(url: String, headers: Map<String, String>): Map<String, Any?>
    fun getAmazonJsonOrEmpty(url: String, headers: Map<String, String>): Map<String, Any?>
    fun requestJson(
        method: String,
        url: String,
        bearer: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpJson
}

@Component
class RestJsonHttpClient(builder: RestClient.Builder) : JsonHttpClient {
    private val client = builder.build()
    private val json = ObjectMapper()
    private val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    override fun postForm(url: String, form: Map<String, String>): Map<String, Any?> {
        val body = LinkedMultiValueMap<String, String>()
        form.forEach { (key, value) -> body.add(key, value) }
        return client.post()
            .uri(url)
            .header("User-Agent", "Reconcile/1.0")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(mapType)
            ?: emptyMap()
    }

    override fun postJson(url: String, body: Map<String, Any?>): Map<String, Any?> =
        client.post()
            .uri(url)
            .header("User-Agent", "Reconcile/1.0")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(mapType)
            ?: emptyMap()

    override fun getJson(url: String, bearer: String): Map<String, Any?> =
        client.get()
            .uri(url)
            .header("User-Agent", "Reconcile/1.0")
            .header("Authorization", "Bearer $bearer")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(mapType)
            ?: emptyMap()

    override fun getAmazonJson(url: String, headers: Map<String, String>): Map<String, Any?> {
        val request = client.get()
            .uri(url)
            .header("User-Agent", "Reconcile/1.0")
            .accept(MediaType.APPLICATION_JSON)
        headers.forEach { (name, value) -> request.header(name, value) }
        return request.retrieve().body(mapType) ?: emptyMap()
    }

    override fun getAmazonJsonOrEmpty(url: String, headers: Map<String, String>): Map<String, Any?> =
        try {
            getAmazonJson(url, headers)
        } catch (_: RestClientResponseException) {
            emptyMap()
        }

    override fun getJsonOrEmpty(url: String, bearer: String): Map<String, Any?> =
        try {
            getJson(url, bearer)
        } catch (_: RestClientResponseException) {
            emptyMap()
        }

    override fun requestJson(
        method: String,
        url: String,
        bearer: String,
        body: Any?,
        headers: Map<String, String>,
    ): HttpJson {
        val httpMethod = when (method) {
            "GET" -> HttpMethod.GET
            "PUT" -> HttpMethod.PUT
            "POST" -> HttpMethod.POST
            else -> throw IllegalArgumentException(apiMessage("auth.httpMethod"))
        }
        val request = client.method(httpMethod).uri(url)
        request.header("User-Agent", "Reconcile/1.0")
        request.accept(MediaType.APPLICATION_JSON)
        headers.forEach { (name, value) ->
            if (!name.equals("Authorization", ignoreCase = true)) request.header(name, value)
        }
        request.header("Authorization", "Bearer $bearer")
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
            request.body(body)
        }
        return try {
            val entity = request.retrieve().toEntity(String::class.java)
            HttpJson(entity.statusCode.value(), parseJson(entity.body), singleHeaders(entity.headers))
        } catch (ex: RestClientResponseException) {
            HttpJson(ex.statusCode.value(), parseJson(ex.responseBodyAsString), singleHeaders(ex.responseHeaders))
        }
    }

    private fun parseJson(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.readValue(raw, Any::class.java) }.getOrNull()
    }

    private fun singleHeaders(headers: HttpHeaders?): Map<String, String> = headers?.toSingleValueMap() ?: emptyMap()
}
