package br.com.reconcile.reconciliation.domain

import br.com.reconcile.shared.domain.Money
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SettlementCalculationEngineTest {
    private val engine = SettlementCalculationEngine()

    @Test
    fun `calculates expected settlement for the canonical 1000 example`() {
        val result = engine.calculate(
            CalculationInput(
                gross = Money.of("1000.00"),
                components = listOf(
                    CalculationComponent("Comissão marketplace", Money.of("-120.00")),
                    CalculationComponent("Frete", Money.of("-22.90")),
                    CalculationComponent("Tarifa de pagamento", Money.of("-14.79")),
                ),
                actual = Money.of("812.31"),
            ),
        )

        assertEquals("sce-v1.4", result.version)
        assertEquals(Money.of("842.31"), result.expected)
        assertEquals(Money.of("812.31"), result.actual)
        assertEquals(Money.of("30.00"), result.difference)
    }
}
