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

  fun formatDaysUntilDue(
    dueDateIso: String?,
    today: LocalDate = today()
  ): String? {
    val parsed = parseDate(dueDateIso) ?: return null
    val delta = daysUntil(today, parsed)
    return when {
      delta == 0L -> "Due today"
      delta == 1L -> "Tomorrow"
      delta > 1L -> "In $delta days"
      delta == -1L -> "1 day overdue"
      else -> "${-delta} days overdue"
    }
  }

  private val movablePhHolidays: Set<LocalDate> = setOf(
    // 2025
    LocalDate.of(2025, 4, 17), // Maundy Thursday
    LocalDate.of(2025, 4, 18), // Good Friday
    LocalDate.of(2025, 8, 25), // National Heroes Day
    // 2026 (Proclamation No. 1006)
    LocalDate.of(2026, 4, 2),  // Maundy Thursday
    LocalDate.of(2026, 4, 3),  // Good Friday
    LocalDate.of(2026, 8, 31), // National Heroes Day
    // 2027 (Proclamation No. 1427)
    LocalDate.of(2027, 3, 25), // Maundy Thursday
    LocalDate.of(2027, 3, 26), // Good Friday
    LocalDate.of(2027, 8, 30), // National Heroes Day
    // 2028
    LocalDate.of(2028, 4, 13), // Maundy Thursday
    LocalDate.of(2028, 4, 14), // Good Friday
    LocalDate.of(2028, 8, 28)  // National Heroes Day
  )

  /**
   * Philippine Regular Holidays (fixed dates + Holy Thursday/Good Friday/National Heroes Day)
   * used to recognize and project single-day lender schedule shifts such as Nov 30 -> Dec 01.
   */
  fun isPhilippineRegularHoliday(date: LocalDate): Boolean {
    val m = date.monthValue
    val d = date.dayOfMonth
    val fixed = (m == 1 && d == 1) ||   // New Year's Day
      (m == 4 && d == 9) ||             // Araw ng Kagitingan
      (m == 5 && d == 1) ||             // Labor Day
      (m == 6 && d == 12) ||            // Independence Day
      (m == 11 && d == 30) ||           // Bonifacio Day
      (m == 12 && d == 25) ||           // Christmas Day
      (m == 12 && d == 30)              // Rizal Day
    return fixed || date in movablePhHolidays
  }

  fun adjustForPhilippineHoliday(
    date: LocalDate,
    skipWeekends: Boolean = false
  ): LocalDate {
    var cursor = date
    var guard = 0
    while (guard < 7 && (isPhilippineRegularHoliday(cursor) ||
        (skipWeekends && (cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY)))
    ) {
      cursor = cursor.plusDays(1)
      guard++
    }
    return cursor
  }

  /**
   * If [date] falls 1 day after a Philippine Regular Holiday, recovers the unshifted anchor date
   * so subsequent weekly/biweekly installments snap back to the original weekday anchor.
   */
  fun inferUnshiftedAnchorDate(date: LocalDate): LocalDate =
    if (isPhilippineRegularHoliday(date.minusDays(1))) date.minusDays(1) else date

  /**
   * Generates [count] scheduled dates starting at [nextDueDate], stepping cleanly from an unshifted
   * anchor ([anchorStart] or inferred pre-holiday anchor) and shifting individual installments that
   * fall on Philippine Regular Holidays forward to the next banking day.
   */
  fun generateProjectedScheduleDates(
    nextDueDate: LocalDate,
    count: Int,
    frequency: RepaymentFrequency,
    customIntervalDays: Int? = null,
    adjustHolidays: Boolean = true,
    anchorStart: LocalDate? = null
  ): List<LocalDate> {
    if (count <= 0) return emptyList()
    if (count == 1 || frequency == RepaymentFrequency.ONE_TIME) {
      return listOf(nextDueDate)
    }
    val effectiveAnchor = anchorStart ?: inferUnshiftedAnchorDate(nextDueDate)
    val result = ArrayList<LocalDate>(count)
    result.add(nextDueDate)

    if (frequency == RepaymentFrequency.TWICE_MONTHLY) {
      var cursor = nextDueDate
      for (step in 1 until count) {
        cursor = if (cursor.dayOfMonth <= 15) {
          cursor.withDayOfMonth(cursor.lengthOfMonth())
        } else {
          cursor.plusMonths(1).withDayOfMonth(15)
        }
        result.add(cursor)
      }
      return result
    }

    for (step in 1 until count) {
      val baseDate = when (frequency) {
        RepaymentFrequency.DAILY -> effectiveAnchor.plusDays(step.toLong())
        RepaymentFrequency.WEEKLY -> effectiveAnchor.plusWeeks(step.toLong())
        RepaymentFrequency.EVERY_14_DAYS -> effectiveAnchor.plusDays(14L * step.toLong())
        RepaymentFrequency.MONTHLY -> effectiveAnchor.plusMonths(step.toLong())
        RepaymentFrequency.QUARTERLY -> effectiveAnchor.plusMonths(3L * step.toLong())
        RepaymentFrequency.CUSTOM_DAYS -> {
          val days = customIntervalDays?.takeIf { it > 0 } ?: return result
          effectiveAnchor.plusDays(days.toLong() * step.toLong())
        }
        else -> return result
      }
      val adjusted = if (adjustHolidays &&
        (frequency == RepaymentFrequency.WEEKLY ||
          frequency == RepaymentFrequency.EVERY_14_DAYS ||
          frequency == RepaymentFrequency.CUSTOM_DAYS)
      ) {
        adjustForPhilippineHoliday(baseDate)
      } else {
        baseDate
      }
      result.add(adjusted)
    }
    return result
  }

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
      RepaymentFrequency.DAILY -> fromDate.plusDays(1)
      RepaymentFrequency.WEEKLY -> fromDate.plusWeeks(1)
      RepaymentFrequency.EVERY_14_DAYS -> fromDate.plusDays(14)
      RepaymentFrequency.MONTHLY -> fromDate.plusMonths(1)
      RepaymentFrequency.QUARTERLY -> fromDate.plusMonths(3)
      RepaymentFrequency.CUSTOM_DAYS -> {
        val days = customIntervalDays?.takeIf { it > 0 } ?: return null
        fromDate.plusDays(days.toLong())
      }
      RepaymentFrequency.TWICE_MONTHLY,
      RepaymentFrequency.ONE_TIME,
      RepaymentFrequency.IRREGULAR,
      RepaymentFrequency.UNKNOWN -> null
    }
  }
}
