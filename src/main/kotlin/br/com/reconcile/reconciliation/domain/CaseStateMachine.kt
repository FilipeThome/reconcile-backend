package br.com.reconcile.reconciliation.domain

import br.com.reconcile.shared.api.apiMessage

class IllegalCaseTransitionException(message: String) : IllegalStateException(message)

enum class CaseStatus {
    aberta,
    em_analise,
    resolvida,
    ignorada,
}

object CaseStateMachine {
    private val allowed = mapOf(
        CaseStatus.aberta to setOf(CaseStatus.em_analise, CaseStatus.resolvida, CaseStatus.ignorada),
        CaseStatus.em_analise to setOf(CaseStatus.resolvida, CaseStatus.ignorada, CaseStatus.aberta),
        CaseStatus.resolvida to setOf(CaseStatus.aberta),
        CaseStatus.ignorada to setOf(CaseStatus.aberta),
    )

    fun transition(from: CaseStatus, to: CaseStatus): CaseStatus {
        val next = allowed[from].orEmpty()
        if (to !in next) {
            throw IllegalCaseTransitionException(apiMessage("case.transition", from, to))
        }
        return to
    }
}
