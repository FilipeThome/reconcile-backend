package br.com.reconcile.reconciliation.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CaseStateMachineTest {
    @Test
    fun `allows operational transitions`() {
        assertEquals(CaseStatus.em_analise, CaseStateMachine.transition(CaseStatus.aberta, CaseStatus.em_analise))
        assertEquals(CaseStatus.resolvida, CaseStateMachine.transition(CaseStatus.em_analise, CaseStatus.resolvida))
        assertEquals(CaseStatus.aberta, CaseStateMachine.transition(CaseStatus.resolvida, CaseStatus.aberta))
        assertEquals(CaseStatus.ignorada, CaseStateMachine.transition(CaseStatus.aberta, CaseStatus.ignorada))
        assertEquals(CaseStatus.aberta, CaseStateMachine.transition(CaseStatus.ignorada, CaseStatus.aberta))
        assertEquals(CaseStatus.resolvida, CaseStateMachine.transition(CaseStatus.aberta, CaseStatus.resolvida))
    }

    @Test
    fun `rejects invalid transition`() {
        assertThrows<IllegalCaseTransitionException> {
            CaseStateMachine.transition(CaseStatus.resolvida, CaseStatus.ignorada)
        }
    }
}
