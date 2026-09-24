package br.com.reconcile.shared.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class PeriodFilterTest {
    @Test
    fun `matches the selected month and marketplace`() {
        val filter = PeriodFilter.of("2026-05-01", "2026-05-31", "Mercado Livre", null)
        assertTrue(filter.matchesInstant(Instant.parse("2026-05-04T11:22:00Z")))
        assertFalse(filter.matchesInstant(Instant.parse("2026-04-20T10:00:00Z")))
        assertTrue(filter.matchesMarketplace("Mercado Livre"))
        assertFalse(filter.matchesMarketplace("Shopee"))
        assertEquals("01/05/2026 — 31/05/2026", filter.label())
    }

    @Test
    fun `previous period has the same length`() {
        val filter = PeriodFilter.of("2026-05-01", "2026-05-31", null, null).previous()
        assertEquals(LocalDate.parse("2026-03-31"), filter.from)
        assertEquals(LocalDate.parse("2026-04-30"), filter.to)
    }

    @Test
    fun `handles empty, invalid and unbounded filters`() {
        val open = PeriodFilter.of(null, "  ", "  ", "")
        assertTrue(open.matchesInstant(null))
        assertTrue(open.matchesInstant(Instant.parse("2020-01-01T00:00:00Z")))
        assertTrue(open.matchesDate(null))
        assertTrue(open.matchesMarketplace(null))
        assertEquals("Período atual", open.label())
        assertEquals(null, open.previous().from)
        assertEquals(open, open.defaultToPeriod(null))
        val month = open.defaultToPeriod("2026-05")
        assertEquals(LocalDate.parse("2026-05-01"), month.from)
        val pinned = open.copy(from = LocalDate.parse("2026-05-01"))
        assertEquals(pinned, pinned.defaultToPeriod("2026-05"))
        assertEquals(open, open.defaultToPeriod("nao-mes"))
        org.junit.jupiter.api.assertThrows<UnprocessableException> {
            PeriodFilter.of("2026-13-40", null, null, null)
        }
    }
}
