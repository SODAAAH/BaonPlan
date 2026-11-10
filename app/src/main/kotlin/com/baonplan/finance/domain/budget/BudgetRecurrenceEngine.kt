package com.baonplan.finance.domain.budget

import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import com.baonplan.finance.data.local.entity.IncomeOccurrenceEntity
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import com.baonplan.finance.domain.model.BudgetPeriodStyle
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.TwiceMonthlyPattern
import com.baonplan.finance.domain.model.WeeklyCycleMode
import com.baonplan.finance.domain.util.ManilaTime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object BudgetRecurrenceEngine {

  private val monthDayFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
  private val dayOnlyFormatter = DateTimeFormatter.ofPattern("d", Locale.US)
  private val fullShortFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

  fun parseCustomDates(csv: String): List<LocalDate> {
    if (csv.isBlank()) return emptyList()
    return csv.split(',', ';', '\n', '\r')
      .mapNotNull { token -> ManilaTime.parseDate(token.trim()) }
      .distinct()
      .sorted()
  }

  fun formatCustomDatesCsv(dates: List<LocalDate>): String =
    dates.distinct().sorted().joinToString(",") { ManilaTime.formatIso(it) }

  /**
   * Computes a clamped date in [yearMonth] using the un-clamped [anchorDay] so sequences like
   * 31st -> Feb 28/29 -> Mar 31 preserve the original payday anchor across months.
   */
  fun clampedDateInMonth(yearMonth: YearMonth, anchorDay: Int): LocalDate =
    yearMonth.atDay(anchorDay.coerceIn(1, yearMonth.lengthOfMonth()))

  fun resolvePrimarySource(
    profile: BudgetProfileEntity?,
    sources: List<IncomeSourceEntity>
  ): IncomeSourceEntity? {
    val profileSource = profile?.primarySourceId?.let { id -> sources.firstOrNull { it.id == id && it.isActive } }
    if (profileSource != null) return profileSource
    return sources.firstOrNull { it.isPrimary && it.isActive && !it.isPaused }
      ?: sources.firstOrNull { it.isActive && !it.isPaused }
      ?: sources.firstOrNull { it.isPrimary && it.isActive }
      ?: sources.firstOrNull { it.isActive }
      ?: profile?.primarySourceId?.let { id -> sources.firstOrNull { it.id == id } }
      ?: sources.firstOrNull()
  }

  fun resolvePeriodWindow(
    today: LocalDate,
    profile: BudgetProfileEntity?,
    sources: List<IncomeSourceEntity>,
    periodOffset: Int = 0
  ): BudgetPeriodWindow {
    val activeProfile = profile ?: BudgetProfileEntity()
    val primary = resolvePrimarySource(activeProfile, sources)
    val primaryAnchor = primary?.let { ManilaTime.parseDate(it.startDateIso) } ?: today

    val effectiveStyle = if (activeProfile.periodStyle == BudgetPeriodStyle.AUTO_FROM_PRIMARY) {
      when (primary?.frequency) {
        IncomeFrequency.DAILY -> BudgetPeriodStyle.DAILY
        IncomeFrequency.WEEKLY -> BudgetPeriodStyle.WEEKLY
        IncomeFrequency.EVERY_14_DAYS -> BudgetPeriodStyle.EVERY_14_DAYS
        IncomeFrequency.EVERY_21_DAYS -> BudgetPeriodStyle.CUSTOM_DAYS
        IncomeFrequency.TWICE_MONTHLY -> BudgetPeriodStyle.TWICE_MONTHLY
        IncomeFrequency.MONTHLY, IncomeFrequency.CUSTOM_MONTHS -> BudgetPeriodStyle.MONTHLY
        IncomeFrequency.CUSTOM_DAYS -> BudgetPeriodStyle.CUSTOM_DAYS
        IncomeFrequency.CUSTOM_DATES, IncomeFrequency.ONE_TIME -> BudgetPeriodStyle.MONTHLY
        null -> BudgetPeriodStyle.WEEKLY
      }
    } else {
      activeProfile.periodStyle
    }

    val (startDate, endExclusive, freqLabel) = when (effectiveStyle) {
      BudgetPeriodStyle.DAILY -> {
        val s = today.plusDays(periodOffset.toLong())
        Triple(s, s.plusDays(1), "Daily")
      }

      BudgetPeriodStyle.WEEKLY -> {
        if (activeProfile.weeklyCycleMode == WeeklyCycleMode.ANCHOR_ALIGNED && primary != null) {
          val cycleIdx = Math.floorDiv(ChronoUnit.DAYS.between(primaryAnchor, today), 7L) + periodOffset
          val s = primaryAnchor.plusDays(cycleIdx * 7L)
          Triple(s, s.plusDays(7), "Weekly cycle")
        } else {
          val s = ManilaTime.weekStart(today).plusWeeks(periodOffset.toLong())
          Triple(s, s.plusDays(7), "Weekly (Mon–Sun)")
        }
      }

      BudgetPeriodStyle.EVERY_14_DAYS -> {
        val anchor = primaryAnchor
        val cycleIdx = Math.floorDiv(ChronoUnit.DAYS.between(anchor, today), 14L) + periodOffset
        val s = anchor.plusDays(cycleIdx * 14L)
        Triple(s, s.plusDays(14), "Every 14 days")
      }

      BudgetPeriodStyle.CUSTOM_DAYS -> {
        val days = when {
          activeProfile.periodStyle == BudgetPeriodStyle.CUSTOM_DAYS ->
            activeProfile.customPeriodDays?.takeIf { it > 0 } ?: 7
          primary?.frequency == IncomeFrequency.EVERY_21_DAYS -> 21
          primary?.frequency == IncomeFrequency.CUSTOM_DAYS ->
            primary.customIntervalDays?.takeIf { it > 0 } ?: 7
          else -> activeProfile.customPeriodDays?.takeIf { it > 0 } ?: 7
        }
        val cycleIdx = Math.floorDiv(ChronoUnit.DAYS.between(primaryAnchor, today), days.toLong()) + periodOffset
        val s = primaryAnchor.plusDays(cycleIdx * days.toLong())
        val label = if (days == 21) "Every 21 days" else "Every $days days"
        Triple(s, s.plusDays(days.toLong()), label)
      }

      BudgetPeriodStyle.TWICE_MONTHLY -> {
        val pattern = primary?.twiceMonthlyPattern ?: TwiceMonthlyPattern.FIRST_AND_FIFTEENTH
        val d1 = primary?.twiceMonthlyDay1
        val d2 = primary?.twiceMonthlyDay2
        val (s, e) = twiceMonthlyWindowForOffset(today, pattern, d1, d2, periodOffset)
        Triple(s, e, "Twice monthly")
      }

      BudgetPeriodStyle.MONTHLY, BudgetPeriodStyle.AUTO_FROM_PRIMARY -> {
        val customMonths = if (
          activeProfile.periodStyle == BudgetPeriodStyle.AUTO_FROM_PRIMARY &&
          primary?.frequency == IncomeFrequency.CUSTOM_MONTHS
        ) {
          primary.customIntervalMonths?.takeIf { it > 0 }
        } else null

        if (customMonths != null || activeProfile.monthlyCycleMode == MonthlyCycleMode.PAYDAY_CYCLE) {
          val stepMonths = (customMonths ?: 1).toLong()
          val anchorDay = primary?.anchorDayOfMonth ?: primaryAnchor.dayOfMonth
          val (s, e) = paydayMonthlyWindowForOffset(today, primaryAnchor, anchorDay, stepMonths, periodOffset)
          val lbl = if (stepMonths > 1L) "Every $stepMonths months" else "Monthly cycle"
          Triple(s, e, lbl)
        } else {
          val ym = YearMonth.from(today).plusMonths(periodOffset.toLong())
          val s = ym.atDay(1)
          val e = ym.plusMonths(1).atDay(1)
          Triple(s, e, "Monthly")
        }
      }
    }

    return BudgetPeriodWindow(
      periodOffset = periodOffset,
      startDate = startDate,
      endDateExclusive = endExclusive,
      label = formatPeriodLabel(startDate, endExclusive, today),
      frequencyLabel = freqLabel
    )
  }

  private fun twiceMonthlyDaysInMonth(
    ym: YearMonth,
    pattern: TwiceMonthlyPattern,
    customDay1: Int?,
    customDay2: Int?
  ): List<LocalDate> {
    val maxDay = ym.lengthOfMonth()
    val rawDays = when (pattern) {
      TwiceMonthlyPattern.FIRST_AND_FIFTEENTH -> listOf(1, 15)
      TwiceMonthlyPattern.FIFTEENTH_AND_END_OF_MONTH -> listOf(15, maxDay)
      TwiceMonthlyPattern.CUSTOM_TWO_DAYS -> {
        val pair = listOfNotNull(customDay1, customDay2)
          .map { it.coerceIn(1, maxDay) }
          .distinct()
          .sorted()
        if (pair.size >= 2) pair.take(2) else listOf(1, 15)
      }
    }
    return rawDays.distinct().sorted().map { ym.atDay(it.coerceIn(1, maxDay)) }
  }

  private fun twiceMonthlyWindowForOffset(
    today: LocalDate,
    pattern: TwiceMonthlyPattern,
    customDay1: Int?,
    customDay2: Int?,
    periodOffset: Int
  ): Pair<LocalDate, LocalDate> {
    // Build a dense ordered ribbon of half-month pay dates centered around today's month.
    val baseYm = YearMonth.from(today)
    val radiusMonths = kotlin.math.abs(periodOffset) / 2 + 4
    val allDates = mutableListOf<LocalDate>()
    for (mOffset in -radiusMonths..radiusMonths) {
      allDates.addAll(twiceMonthlyDaysInMonth(baseYm.plusMonths(mOffset.toLong()), pattern, customDay1, customDay2))
    }
    allDates.sort()
    val currentIdx = allDates.indexOfLast { !it.isAfter(today) }.coerceIn(0, (allDates.size - 2).coerceAtLeast(0))
    val targetIdx = (currentIdx + periodOffset).coerceIn(0, (allDates.size - 2).coerceAtLeast(0))
    return allDates[targetIdx] to allDates[targetIdx + 1]
  }

  private fun paydayMonthlyWindowForOffset(
    today: LocalDate,
    anchor: LocalDate,
    anchorDay: Int,
    stepMonths: Long,
    periodOffset: Int
  ): Pair<LocalDate, LocalDate> {
    val baseYm = YearMonth.from(anchor)
    val approxMonths = ChronoUnit.MONTHS.between(baseYm, YearMonth.from(today))
    var k = Math.floorDiv(approxMonths, stepMonths)
    fun nthPayday(step: Long): LocalDate =
      clampedDateInMonth(baseYm.plusMonths(step * stepMonths), anchorDay)

    while (nthPayday(k).isAfter(today)) k--
    while (!nthPayday(k + 1).isAfter(today)) k++

    val targetK = k + periodOffset.toLong()
    return nthPayday(targetK) to nthPayday(targetK + 1)
  }

  fun formatPeriodLabel(
    startDate: LocalDate,
    endExclusive: LocalDate,
    today: LocalDate = ManilaTime.today()
  ): String {
    val endInclusive = endExclusive.minusDays(1)
    if (startDate == endInclusive) {
      return when (startDate) {
        today -> "Today · ${startDate.format(monthDayFormatter)}"
        today.minusDays(1) -> "Yesterday · ${startDate.format(monthDayFormatter)}"
        today.plusDays(1) -> "Tomorrow · ${startDate.format(monthDayFormatter)}"
        else -> startDate.format(fullShortFormatter)
      }
    }
    if (startDate.dayOfMonth == 1 && endExclusive == startDate.plusMonths(1)) {
      return ManilaTime.formatMonthYear(YearMonth.from(startDate))
    }
    return when {
      startDate.year == endInclusive.year && startDate.month == endInclusive.month ->
        "${startDate.format(monthDayFormatter)}–${endInclusive.format(dayOnlyFormatter)}"
      startDate.year == endInclusive.year ->
        "${startDate.format(monthDayFormatter)}–${endInclusive.format(monthDayFormatter)}"
      else ->
        "${startDate.format(fullShortFormatter)}–${endInclusive.format(fullShortFormatter)}"
    }
  }

  /**
   * Generates active template dates for [source] inside half-open `[windowStart, windowEndExclusive)`.
   * Respects start date, optional end date, pause boundary, and edit effectiveFrom cutoff.
   */
  fun generateScheduledDates(
    source: IncomeSourceEntity,
    windowStart: LocalDate,
    windowEndExclusive: LocalDate,
    ignorePausedState: Boolean = false
  ): List<LocalDate> {
    if (!source.isActive) return emptyList()
    if (!ignorePausedState && source.isPaused && source.pausedFromDateIso == null) return emptyList()
    if (!windowEndExclusive.isAfter(windowStart)) return emptyList()

    val anchor = ManilaTime.parseDate(source.startDateIso) ?: return emptyList()
    val effectiveFrom = ManilaTime.parseDate(source.effectiveFromDateIso)
    val startBound = if (effectiveFrom != null && effectiveFrom.isAfter(anchor)) effectiveFrom else anchor

    val endDate = ManilaTime.parseDate(source.endDateIso)
    val pauseCutoffInclusive = if (!ignorePausedState && source.isPaused) {
      ManilaTime.parseDate(source.pausedFromDateIso)?.minusDays(1)
    } else null

    var lastInclusive = windowEndExclusive.minusDays(1)
    if (endDate != null && endDate.isBefore(lastInclusive)) lastInclusive = endDate
    if (pauseCutoffInclusive != null && pauseCutoffInclusive.isBefore(lastInclusive)) {
      lastInclusive = pauseCutoffInclusive
    }

    val firstInclusive = if (startBound.isAfter(windowStart)) startBound else windowStart
    if (firstInclusive.isAfter(lastInclusive)) return emptyList()

    return when (source.frequency) {
      IncomeFrequency.ONE_TIME -> {
        if (!anchor.isBefore(firstInclusive) && !anchor.isAfter(lastInclusive)) listOf(anchor) else emptyList()
      }

      IncomeFrequency.CUSTOM_DATES -> {
        parseCustomDates(source.customDatesCsv).filter {
          !it.isBefore(firstInclusive) && !it.isAfter(lastInclusive)
        }
      }

      IncomeFrequency.DAILY -> generateDayStepped(anchor, 1L, firstInclusive, lastInclusive)
      IncomeFrequency.WEEKLY -> generateDayStepped(anchor, 7L, firstInclusive, lastInclusive)
      IncomeFrequency.EVERY_14_DAYS -> generateDayStepped(anchor, 14L, firstInclusive, lastInclusive)
      IncomeFrequency.EVERY_21_DAYS -> generateDayStepped(anchor, 21L, firstInclusive, lastInclusive)
      IncomeFrequency.CUSTOM_DAYS -> {
        val days = source.customIntervalDays?.takeIf { it > 0 }?.toLong() ?: return emptyList()
        generateDayStepped(anchor, days, firstInclusive, lastInclusive)
      }

      IncomeFrequency.MONTHLY -> {
        val anchorDay = source.anchorDayOfMonth?.coerceIn(1, 31) ?: anchor.dayOfMonth
        generateMonthStepped(anchor, anchorDay, 1L, firstInclusive, lastInclusive)
      }

      IncomeFrequency.CUSTOM_MONTHS -> {
        val months = source.customIntervalMonths?.takeIf { it > 0 }?.toLong() ?: return emptyList()
        val anchorDay = source.anchorDayOfMonth?.coerceIn(1, 31) ?: anchor.dayOfMonth
        generateMonthStepped(anchor, anchorDay, months, firstInclusive, lastInclusive)
      }

      IncomeFrequency.TWICE_MONTHLY -> {
        val results = mutableListOf<LocalDate>()
        var cursorYm = YearMonth.from(firstInclusive)
        val endYm = YearMonth.from(lastInclusive)
        while (!cursorYm.isAfter(endYm)) {
          val candidates = twiceMonthlyDaysInMonth(
            ym = cursorYm,
            pattern = source.twiceMonthlyPattern,
            customDay1 = source.twiceMonthlyDay1,
            customDay2 = source.twiceMonthlyDay2
          )
          for (d in candidates) {
            if (!d.isBefore(firstInclusive) && !d.isAfter(lastInclusive)) {
              results.add(d)
            }
          }
          cursorYm = cursorYm.plusMonths(1)
        }
        results
      }
    }
  }

  private fun generateDayStepped(
    anchor: LocalDate,
    stepDays: Long,
    firstInclusive: LocalDate,
    lastInclusive: LocalDate
  ): List<LocalDate> {
    if (stepDays <= 0L) return emptyList()
    val diff = ChronoUnit.DAYS.between(anchor, firstInclusive).coerceAtLeast(0L)
    val startK = (diff + stepDays - 1L) / stepDays
    val result = mutableListOf<LocalDate>()
    var k = startK
    while (true) {
      val candidate = anchor.plusDays(k * stepDays)
      if (candidate.isAfter(lastInclusive)) break
      if (!candidate.isBefore(firstInclusive)) {
        result.add(candidate)
      }
      k++
    }
    return result
  }

  private fun generateMonthStepped(
    anchor: LocalDate,
    anchorDay: Int,
    stepMonths: Long,
    firstInclusive: LocalDate,
    lastInclusive: LocalDate
  ): List<LocalDate> {
    if (stepMonths <= 0L) return emptyList()
    val baseYm = YearMonth.from(anchor)
    val monthsDiff = ChronoUnit.MONTHS.between(baseYm, YearMonth.from(firstInclusive)).coerceAtLeast(0L)
    var k = (monthsDiff / stepMonths - 1L).coerceAtLeast(0L)
    val result = mutableListOf<LocalDate>()
    while (true) {
      val candidate = clampedDateInMonth(baseYm.plusMonths(k * stepMonths), anchorDay)
      if (candidate.isAfter(lastInclusive)) break
      if (!candidate.isBefore(firstInclusive)) {
        result.add(candidate)
      }
      k++
    }
    return result
  }

  /**
   * Resolves all expected/confirmed/missed/cancelled income occurrences relevant to [window].
   * Ensures persisted occurrences override synthetic template rows on `(sourceId, expectedDate)`
   * and preserves confirmed occurrences whose `actualReceivedDate` landed in [window].
   */
  fun resolveOccurrencesForWindow(
    sources: List<IncomeSourceEntity>,
    persistedOccurrences: List<IncomeOccurrenceEntity>,
    window: BudgetPeriodWindow,
    primarySourceId: Long? = null
  ): List<ResolvedIncomeOccurrence> {
    val sourcesById = sources.associateBy { it.id }
    val persistedByKey = HashMap<Pair<Long, LocalDate>, IncomeOccurrenceEntity>()
    val result = mutableListOf<ResolvedIncomeOccurrence>()
    val addedOccurrenceIds = HashSet<Long>()

    for (occ in persistedOccurrences) {
      val expectedDate = ManilaTime.parseDate(occ.expectedDateIso) ?: continue
      val actualDate = ManilaTime.parseDate(occ.actualReceivedDateIso)
      if (occ.sourceId != null) {
        persistedByKey[occ.sourceId to expectedDate] = occ
      }
      val expectedInWindow = expectedDate in window
      val actualInWindow = actualDate != null && actualDate in window
      if (expectedInWindow || actualInWindow) {
        val src = occ.sourceId?.let { sourcesById[it] }
        addedOccurrenceIds.add(occ.id)
        result.add(
          ResolvedIncomeOccurrence(
            occurrenceId = occ.id,
            sourceId = occ.sourceId,
            sourceName = src?.name ?: occ.sourceNameSnapshot.ifBlank { "Allowance" },
            expectedDate = expectedDate,
            expectedAmountCents = occ.expectedAmountCents,
            actualReceivedAmountCents = occ.actualReceivedAmountCents,
            actualReceivedDate = actualDate,
            status = occ.status,
            isVariableAmount = src?.isVariableAmount ?: false,
            isFromPrimarySource = (occ.sourceId != null && occ.sourceId == primarySourceId) ||
              (src?.isPrimary == true),
            linkedAllowanceRecordId = occ.linkedAllowanceRecordId,
            notes = occ.notes
          )
        )
      }
    }

    for (source in sources) {
      val generatedDates = generateScheduledDates(source, window.startDate, window.endDateExclusive)
      for (date in generatedDates) {
        if (persistedByKey.containsKey(source.id to date)) continue
        result.add(
          ResolvedIncomeOccurrence(
            occurrenceId = null,
            sourceId = source.id,
            sourceName = source.name,
            expectedDate = date,
            expectedAmountCents = source.expectedAmountCents,
            actualReceivedAmountCents = 0L,
            actualReceivedDate = null,
            status = IncomeOccurrenceStatus.EXPECTED,
            isVariableAmount = source.isVariableAmount,
            isFromPrimarySource = source.id == primarySourceId || source.isPrimary,
            linkedAllowanceRecordId = null,
            notes = source.notes
          )
        )
      }
    }

    return result.sortedWith(
      compareBy<ResolvedIncomeOccurrence> { it.expectedDate }
        .thenByDescending { it.isFromPrimarySource }
        .thenBy { it.sourceName }
    )
  }

  /**
   * Finds the soonest future expected occurrence strictly after or on [fromDate] across active sources.
   */
  fun findNextExpectedIncome(
    sources: List<IncomeSourceEntity>,
    persistedOccurrences: List<IncomeOccurrenceEntity>,
    fromDate: LocalDate,
    horizonDays: Long = 180L
  ): ResolvedIncomeOccurrence? {
    val window = BudgetPeriodWindow(
      periodOffset = 0,
      startDate = fromDate,
      endDateExclusive = fromDate.plusDays(horizonDays.coerceAtLeast(1L)),
      label = "",
      frequencyLabel = ""
    )
    return resolveOccurrencesForWindow(sources, persistedOccurrences, window)
      .filter { it.status == IncomeOccurrenceStatus.EXPECTED && !it.expectedDate.isBefore(fromDate) }
      .minByOrNull { it.expectedDate }
  }

  /**
   * Part 14: Exact frequency normalization for long-term annual/monthly comparisons.
   * Uses `BigDecimal` without Double rounding drift and never alters exact period cash-flow.
   */
  fun calculateFrequencyNormalization(
    sources: List<IncomeSourceEntity>,
    referenceYear: Int = ManilaTime.today().year
  ): FrequencyNormalizationSummary {
    val yearStart = LocalDate.of(referenceYear, 1, 1)
    val yearEndExclusive = LocalDate.of(referenceYear + 1, 1, 1)
    val daysInYear = BigDecimal.valueOf(yearStart.lengthOfYear().toLong())
    val twelve = BigDecimal.valueOf(12L)

    val activeSources = sources.filter { it.isActive && !it.isPaused }
    val items = activeSources.map { src ->
      val base = BigDecimal.valueOf(src.expectedAmountCents.coerceAtLeast(0L))
      val annualBd: BigDecimal = when (src.frequency) {
        IncomeFrequency.DAILY -> base.multiply(daysInYear)
        IncomeFrequency.WEEKLY -> base.multiply(BigDecimal.valueOf(52L))
        IncomeFrequency.EVERY_14_DAYS -> base.multiply(BigDecimal.valueOf(26L))
        IncomeFrequency.EVERY_21_DAYS ->
          base.multiply(daysInYear).divide(BigDecimal.valueOf(21L), 0, RoundingMode.HALF_UP)
        IncomeFrequency.TWICE_MONTHLY -> base.multiply(BigDecimal.valueOf(24L))
        IncomeFrequency.MONTHLY -> base.multiply(twelve)
        IncomeFrequency.CUSTOM_DAYS -> {
          val n = src.customIntervalDays?.takeIf { it > 0 }?.toLong()
          if (n == null) BigDecimal.ZERO
          else base.multiply(daysInYear).divide(BigDecimal.valueOf(n), 0, RoundingMode.HALF_UP)
        }
        IncomeFrequency.CUSTOM_MONTHS -> {
          val m = src.customIntervalMonths?.takeIf { it > 0 }?.toLong()
          if (m == null) BigDecimal.ZERO
          else base.multiply(twelve).divide(BigDecimal.valueOf(m), 0, RoundingMode.HALF_UP)
        }
        IncomeFrequency.CUSTOM_DATES -> {
          val count = generateScheduledDates(src, yearStart, yearEndExclusive).size.toLong()
          base.multiply(BigDecimal.valueOf(count))
        }
        IncomeFrequency.ONE_TIME -> {
          val count = generateScheduledDates(src, yearStart, yearEndExclusive).size.toLong()
          base.multiply(BigDecimal.valueOf(count))
        }
      }

      val annualCents = annualBd.setScale(0, RoundingMode.HALF_UP).longValueExact()
      val monthlyCents = annualBd.divide(twelve, 0, RoundingMode.HALF_UP).longValueExact()

      FrequencyNormalizationItem(
        sourceId = src.id,
        name = src.name,
        frequency = src.frequency,
        perOccurrenceCents = src.expectedAmountCents,
        normalizedMonthlyCents = monthlyCents,
        normalizedAnnualCents = annualCents
      )
    }

    return FrequencyNormalizationSummary(
      items = items,
      totalNormalizedMonthlyCents = items.sumOf { it.normalizedMonthlyCents },
      totalNormalizedAnnualCents = items.sumOf { it.normalizedAnnualCents }
    )
  }
}
