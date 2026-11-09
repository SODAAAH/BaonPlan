package com.baonplan.finance.domain.util

import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.domain.model.RepaymentFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object ManilaTime {
  val ZONE: ZoneId = ZoneId.of("Asia/Manila")

  private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
  private val displayDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
  private val fullDisplayDateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
  private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
  private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
  private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
  private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)
  private val time12hFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

  fun formatTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Never"
    return Instant.ofEpochMilli(epochMillis).atZone(ZONE).format(dateTimeFormatter)
  }

  fun formatTime12h(hour: Int, minute: Int): String =
    LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).format(time12hFormatter)

  fun today(now: Instant = Instant.now()): LocalDate = now.atZone(ZONE).toLocalDate()

  fun greetingForNow(now: Instant = Instant.now()): String {
    val hour = now.atZone(ZONE).hour
    return when {
      hour in 5..11 -> "Good morning"
      hour in 12..17 -> "Good afternoon"
      else -> "Good evening"
    }
  }

  fun nowMillis(): Long = System.currentTimeMillis()

  fun parseDate(isoDate: String?): LocalDate? {
    if (isoDate.isNullOrBlank()) return null
    return try {
      LocalDate.parse(isoDate, isoDateFormatter)
    } catch (_: Exception) {
      null
    }
  }

  fun formatIso(date: LocalDate): String = date.format(isoDateFormatter)

  fun formatDisplay(isoDate: String?): String {
    val parsed = parseDate(isoDate) ?: return "Unconfirmed"
    return parsed.format(displayDateFormatter)
  }

  fun formatFullDisplay(isoDate: String?): String {
    val parsed = parseDate(isoDate) ?: return "Date unconfirmed"
    return parsed.format(fullDisplayDateFormatter)
  }

  fun formatDisplay(date: LocalDate): String = date.format(displayDateFormatter)

  fun formatShort(isoDate: String?): String {
    val parsed = parseDate(isoDate) ?: return "TBD"
    return parsed.format(shortDateFormatter)
  }

  fun formatDayHeader(isoDate: String): String {
    val parsed = parseDate(isoDate) ?: return isoDate
    return parsed.format(dayOfWeekFormatter)
  }

  fun formatMonthYear(yearMonth: YearMonth): String = yearMonth.format(monthYearFormatter)

  fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

  fun weekEnd(date: LocalDate): LocalDate =
    weekStart(date).plusDays(6)

  fun isInWeek(dateIso: String, weekStart: LocalDate): Boolean {
    val date = parseDate(dateIso) ?: return false
    val end = weekStart.plusDays(6)
    return !date.isBefore(weekStart) && !date.isAfter(end)
  }

  fun daysUntil(today: LocalDate, targetDate: LocalDate): Long =
    ChronoUnit.DAYS.between(today, targetDate)

  fun classifyUrgency(
    today: LocalDate,
    dueDateIso: String?,
    dateStatus: DueDateStatus
  ): PaymentUrgency {
    if (dateStatus == DueDateStatus.UNKNOWN) return PaymentUrgency.UNKNOWN
    val dueDate = parseDate(dueDateIso) ?: return when (dateStatus) {
      DueDateStatus.UNCONFIRMED -> PaymentUrgency.UNCONFIRMED
      else -> PaymentUrgency.UNKNOWN
    }
    val delta = daysUntil(today, dueDate)
    return when {
      delta < 0L -> PaymentUrgency.OVERDUE
      delta == 0L -> PaymentUrgency.DUE_TODAY
      delta == 1L -> PaymentUrgency.DUE_TOMORROW
      else -> PaymentUrgency.UPCOMING
    }
  }

  /**
   * Advances a known date by the given repayment frequency.
   * Returns null for UNKNOWN or ONE_TIME — never fabricates or guesses intervals.
   */
  fun advanceDate(
    fromDate: LocalDate,
    frequency: RepaymentFrequency,
    customIntervalDays: Int? = null
  ): LocalDate? {
    return when (frequency) {
      RepaymentFrequency.WEEKLY -> fromDate.plusWeeks(1)
      RepaymentFrequency.EVERY_14_DAYS -> fromDate.plusDays(14)
      RepaymentFrequency.MONTHLY -> fromDate.plusMonths(1)
      RepaymentFrequency.CUSTOM_DAYS -> {
        val days = customIntervalDays?.takeIf { it > 0 } ?: return null
        fromDate.plusDays(days.toLong())
      }
      RepaymentFrequency.ONE_TIME, RepaymentFrequency.IRREGULAR, RepaymentFrequency.UNKNOWN -> null
    }
  }
}
