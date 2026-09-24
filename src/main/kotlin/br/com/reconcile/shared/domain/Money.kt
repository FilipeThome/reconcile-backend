package br.com.reconcile.shared.domain

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)

    fun of(value: String): BigDecimal = BigDecimal(value).setScale(2, RoundingMode.HALF_UP)

    fun of(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)

    fun of(value: Number): BigDecimal = when (value) {
        is BigDecimal -> scale(value)
        is Int, is Long, is Short -> BigDecimal.valueOf(value.toLong()).setScale(2)
        else -> of(value.toDouble())
    }

    fun scale(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
}
