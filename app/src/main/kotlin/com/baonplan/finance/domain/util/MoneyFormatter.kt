package com.baonplan.finance.domain.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object MoneyFormatter {
  private val symbols = DecimalFormatSymbols(Locale.US).apply {
    groupingSeparator = ','
    decimalSeparator = '.'
  }

  private fun twoDecimalFormat(): DecimalFormat =
    DecimalFormat("#,##0.00", symbols).apply {
      roundingMode = RoundingMode.HALF_UP
    }

  private fun wholeNumberFormat(): DecimalFormat =
    DecimalFormat("#,##0", symbols)

  /**
   * Formats integer centavos into Philippine Peso string (e.g., 209617L -> "₱2,096.17").
   * Uses BigDecimal scaled by 100 — never Float or Double.
   */
  fun formatPeso(cents: Long): String {
    val negative = cents < 0
    val absCents = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(cents)
    val decimal = BigDecimal(absCents).divide(BigDecimal(100), 2, RoundingMode.UNNECESSARY)
    val formatted = twoDecimalFormat().format(decimal)
    return if (negative) "-₱$formatted" else "₱$formatted"
  }

  /**
   * Formats whole pesos cleanly without trailing .00 when exact (e.g., 40000L -> "₱400", 33507L -> "₱335.07").
   */
  fun formatPesoCompact(cents: Long): String {
    val negative = cents < 0
    val absCents = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(cents)
    if (absCents % 100L == 0L) {
      val whole = absCents / 100L
      val formatted = wholeNumberFormat().format(whole)
      return if (negative) "-₱$formatted" else "₱$formatted"
    }
    return formatPeso(cents)
  }

  /**
   * Formats plain decimal input without currency symbol (e.g., 33507L -> "335.07") for editable TextFields.
   */
  fun formatEditableAmount(cents: Long?): String {
    if (cents == null) return ""
    val decimal = BigDecimal(cents).divide(BigDecimal(100), 2, RoundingMode.UNNECESSARY)
    return decimal.toPlainString()
  }

  /**
   * Parses a user-entered peso string (e.g., "₱2,096.17", "400", "26.15") into exact integer centavos.
   * Returns null if blank, invalid, negative (unless allowNegative), or has >2 decimal places.
   */
  fun parsePesoToCents(raw: String, allowNegative: Boolean = false): Long? {
    val cleaned = raw
      .trim()
      .replace("₱", "")
      .replace("PHP", "", ignoreCase = true)
      .replace(",", "")
      .replace(" ", "")
    if (cleaned.isEmpty()) return null
    val bd = cleaned.toBigDecimalOrNull() ?: return null
    if (!allowNegative && bd < BigDecimal.ZERO) return null
    if (bd.scale() > 2 && bd.stripTrailingZeros().scale() > 2) return null
    return try {
      bd.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    } catch (_: ArithmeticException) {
      null
    }
  }

  /**
   * Exact BigDecimal multiplication of centavos by a non-negative integer count.
   * Never uses Float or Double.
   */
  fun multiplyCents(amountCents: Long, count: Int): Long {
    if (count <= 0 || amountCents == 0L) return 0L
    return BigDecimal.valueOf(amountCents)
      .multiply(BigDecimal.valueOf(count.toLong()))
      .longValueExact()
  }

  /**
   * Exact BigDecimal ceiling division of centavos (e.g. remainingBalance / installmentAmount).
   * Returns 0 if numerator <= 0 or divisor <= 0.
   */
  fun divideCentsCeil(numeratorCents: Long, divisorCents: Long): Long {
    if (numeratorCents <= 0L || divisorCents <= 0L) return 0L
    return BigDecimal.valueOf(numeratorCents)
      .divide(BigDecimal.valueOf(divisorCents), 0, RoundingMode.CEILING)
      .longValueExact()
  }

  /**
   * Exact BigDecimal summation of centavo values without overflow drift.
   */
  fun sumCentsExact(values: Iterable<Long>): Long {
    var total = BigDecimal.ZERO
    for (v in values) {
      total = total.add(BigDecimal.valueOf(v))
    }
    return total.longValueExact()
  }
}
