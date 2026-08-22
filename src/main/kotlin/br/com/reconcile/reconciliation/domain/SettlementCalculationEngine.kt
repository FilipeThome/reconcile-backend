package br.com.reconcile.reconciliation.domain

import br.com.reconcile.shared.domain.Money
import java.math.BigDecimal

data class CalculationComponent(
    val label: String,
    val amount: BigDecimal,
)

data class CalculationInput(
    val gross: BigDecimal,
    val components: List<CalculationComponent>,
    val actual: BigDecimal = Money.ZERO,
    val version: String = ENGINE_VERSION,
)

data class CalculationResult(
    val version: String,
    val gross: BigDecimal,
    val components: List<CalculationComponent>,
    val expected: BigDecimal,
    val actual: BigDecimal,
    val difference: BigDecimal,
)

const val ENGINE_VERSION = "sce-v1.4"

class SettlementCalculationEngine {
    fun calculate(input: CalculationInput): CalculationResult {
        val expected = Money.scale(input.gross.add(input.components.fold(Money.ZERO) { acc, item -> acc.add(item.amount) }))
        val actual = Money.scale(input.actual)
        return CalculationResult(
            version = input.version,
            gross = Money.scale(input.gross),
            components = input.components.map { it.copy(amount = Money.scale(it.amount)) },
            expected = expected,
            actual = actual,
            difference = Money.scale(expected.subtract(actual)),
        )
    }
}
