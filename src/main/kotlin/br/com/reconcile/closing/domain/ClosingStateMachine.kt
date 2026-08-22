package br.com.reconcile.closing.domain

import br.com.reconcile.shared.domain.Money
import java.math.BigDecimal
import java.time.Instant

enum class ClosingStatus {
    open,
    closed,
}

data class ClosingSnapshot(
    val status: ClosingStatus,
    val closedAt: Instant?,
    val expected: BigDecimal,
    val received: BigDecimal,
)

data class ClosingDelta(
    val reason: String,
    val amount: BigDecimal,
    val createdAt: Instant,
)

class IllegalClosingTransitionException(message: String) : IllegalStateException(message)

object ClosingStateMachine {
    fun close(current: ClosingSnapshot, at: Instant): ClosingSnapshot {
        if (current.status != ClosingStatus.open) {
            throw IllegalClosingTransitionException("Somente períodos OPEN podem ser fechados")
        }
        return current.copy(status = ClosingStatus.closed, closedAt = at)
    }

    fun lateEvent(current: ClosingSnapshot, reason: String, amount: BigDecimal, at: Instant): ClosingDelta {
        if (current.status != ClosingStatus.closed) {
            throw IllegalClosingTransitionException("Delta auditável só se aplica após CLOSED")
        }
        return ClosingDelta(reason = reason, amount = Money.scale(amount), createdAt = at)
    }
}
