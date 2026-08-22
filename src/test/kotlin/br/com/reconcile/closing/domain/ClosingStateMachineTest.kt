package br.com.reconcile.closing.domain

import br.com.reconcile.shared.domain.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

class ClosingStateMachineTest {
    @Test
    fun `closes an open period and late events become deltas`() {
        val open = ClosingSnapshot(ClosingStatus.open, null, Money.of("100.00"), Money.of("90.00"))
        val closed = ClosingStateMachine.close(open, Instant.parse("2026-06-02T12:00:00Z"))

        assertEquals(ClosingStatus.closed, closed.status)
        assertEquals(Instant.parse("2026-06-02T12:00:00Z"), closed.closedAt)

        val delta = ClosingStateMachine.lateEvent(closed, "Payout tardio", Money.of("15.40"), Instant.parse("2026-06-10T09:00:00Z"))
        assertEquals(Money.of("15.40"), delta.amount)
        assertEquals("Payout tardio", delta.reason)
    }

    @Test
    fun `does not rewrite a closed snapshot and rejects delta while open`() {
        val closed = ClosingSnapshot(ClosingStatus.closed, Instant.parse("2026-05-03T18:40:00Z"), Money.of("10"), Money.of("10"))
        assertThrows<IllegalClosingTransitionException> {
            ClosingStateMachine.close(closed, Instant.now())
        }
        val open = ClosingSnapshot(ClosingStatus.open, null, Money.of("10"), Money.of("10"))
        assertThrows<IllegalClosingTransitionException> {
            ClosingStateMachine.lateEvent(open, "ajuste", Money.of("1"), Instant.now())
        }
    }
}
