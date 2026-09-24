package br.com.reconcile.integration.ingestion

import br.com.reconcile.shared.domain.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

object CatalogJson {
    fun map(raw: Any?): Map<String, Any?> = when (raw) {
        is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }

    fun maps(raw: Any?): List<Map<String, Any?>> = when (raw) {
        is List<*> -> raw.map(::map)
        else -> emptyList()
    }

    fun child(payload: Map<String, Any?>, key: String): Map<String, Any?> = map(payload[key])

    fun text(payload: Map<String, Any?>, key: String): String? =
        payload[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    fun intValue(payload: Map<String, Any?>, key: String, default: Int = 0): Int = when (val raw = payload[key]) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: default
        else -> default
    }

    fun decimal(payload: Map<String, Any?>, key: String): BigDecimal = when (val raw = payload[key]) {
        is Number -> Money.of(raw)
        is String -> raw.toBigDecimalOrNull()?.let(Money::scale) ?: Money.ZERO
        else -> Money.ZERO
    }

    fun instant(payload: Map<String, Any?>, key: String): Instant? {
        val raw = payload[key] ?: return null
        if (raw is Number) return Instant.ofEpochSecond(raw.toLong())
        val text = raw.toString()
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    fun clipped(value: String, max: Int = 64) = value.take(max)
}
