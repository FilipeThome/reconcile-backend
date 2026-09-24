package br.com.reconcile.integration.oauth

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

interface JsonHttpClient {
    fun postForm(url: String, form: Map<String, String>): Map<String, Any?>
    fun postJson(url: String, body: Map<String, Any?>): Map<String, Any?>
    fun getJson(url: String, bearer: String): Map<String, Any?>
    fun getJsonOrEmpty(url: String, bearer: String): Map<String, Any?>
}

@Component
class RestJsonHttpClient(builder: RestClient.Builder) : JsonHttpClient {
    private val client = builder.build()
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

    override fun getJsonOrEmpty(url: String, bearer: String): Map<String, Any?> =
        try {
            getJson(url, bearer)
        } catch (_: RestClientResponseException) {
            emptyMap()
        }
}
