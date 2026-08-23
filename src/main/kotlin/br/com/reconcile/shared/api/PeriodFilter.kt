package br.com.reconcile.shared.api

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

data class PeriodFilter(
    val from: LocalDate?,
    val to: LocalDate?,
    val marketplace: String?,
    val gateway: String?,
) {
    fun matchesInstant(value: Instant?): Boolean {
        if (from == null && to == null) return true
        if (value == null) return false
        return matchesDate(value.atZone(ZoneOffset.UTC).toLocalDate())
    }

    fun matchesDate(day: LocalDate?): Boolean {
        if (from == null && to == null) return true
        if (day == null) return false
        if (from != null && day.isBefore(from)) return false
        if (to != null && day.isAfter(to)) return false
        return true
    }

    fun matchesMarketplace(value: String?) = marketplace.isNullOrBlank() || value == marketplace

    fun matchesGateway(value: String?) = gateway.isNullOrBlank() || value == gateway

    fun defaultToPeriod(period: String?): PeriodFilter {
        if (from != null || to != null || period.isNullOrBlank()) return this
        val month = runCatching { YearMonth.parse(period) }.getOrNull() ?: return this
        return copy(from = month.atDay(1), to = month.atEndOfMonth())
    }

    fun previous(): PeriodFilter {
        if (from == null || to == null) return copy(from = null, to = null)
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        val prevTo = from.minusDays(1)
        return copy(from = prevTo.minusDays(days - 1), to = prevTo)
    }

    fun label(): String {
        if (from == null || to == null) return "Período atual"
        return "${from.format(BR_DAY)} — ${to.format(BR_DAY)}"
    }

    companion object {
        private val BR_DAY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")

        fun of(from: String?, to: String?, marketplace: String?, gateway: String?) = PeriodFilter(
            from = parseDate(from),
            to = parseDate(to),
            marketplace = marketplace?.trim()?.takeIf { it.isNotEmpty() },
            gateway = gateway?.trim()?.takeIf { it.isNotEmpty() },
        )

        private fun parseDate(value: String?): LocalDate? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { LocalDate.parse(raw) }.getOrElse {
                throw UnprocessableException("Data inválida: $raw")
            }
        }
    }
}
