package br.com.reconcile.shared.domain

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)

    fun of(value: String): BigDecimal = BigDecimal(value).setScale(2, RoundingMode.HALF_UP)

    fun of(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)

    fun scale(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
}
