package br.com.reconcile.reconciliation.domain

import java.math.BigDecimal

enum class MatchingLayer {
    ORDER_PAYMENT,
    PAYMENT_SETTLEMENT,
    SETTLEMENT_PAYOUT,
    ORDER_INVOICE,
    GLOBAL_CONSISTENCY,
}

data class MatchingEvidence(
    val field: String,
    val expected: String?,
    val actual: String?,
)

data class MatchingAttempt(
    val layer: MatchingLayer,
    val deterministic: Boolean,
    val confidence: BigDecimal,
    val result: String,
    val evidence: List<MatchingEvidence>,
)

class MatchingEngine {
    fun matchExact(layer: MatchingLayer, leftId: String?, rightId: String?): MatchingAttempt {
        val hit = !leftId.isNullOrBlank() && leftId == rightId
        return MatchingAttempt(
            layer = layer,
            deterministic = true,
            confidence = if (hit) BigDecimal("1.0000") else BigDecimal("0.0000"),
            result = if (hit) "matched" else "unmatched",
            evidence = listOf(MatchingEvidence("id", leftId, rightId)),
        )
    }

    fun score(
        layer: MatchingLayer,
        amountClose: Boolean,
        sameMarketplace: Boolean,
        dateInWindow: Boolean,
    ): MatchingAttempt {
        var score = 0.0
        if (amountClose) score += 0.5
        if (sameMarketplace) score += 0.3
        if (dateInWindow) score += 0.2
        return MatchingAttempt(
            layer = layer,
            deterministic = false,
            confidence = BigDecimal.valueOf(score).setScale(4),
            result = if (score >= 0.8) "review" else "unmatched",
            evidence = emptyList(),
        )
    }
}
