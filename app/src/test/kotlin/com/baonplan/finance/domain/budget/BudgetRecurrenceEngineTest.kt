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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetRecurrenceEngineTest {

  @Test
  fun dailySchedule_generatesExactCalendarDaysIncludingLeapYear() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Daily Baon",
      frequency = IncomeFrequency.DAILY,
      expectedAmountCents = 15_000L,
      startDateIso = "2026-11-10",
      isPrimary = true
    )

    val dates = BudgetRecurrenceEngine.generateScheduledDates(
      source = src,
      windowStart = LocalDate.of(2026, 11, 10),
      windowEndExclusive = LocalDate.of(2026, 11, 15)
    )
    assertEquals(
      listOf(
        LocalDate.of(2026, 11, 10),
        LocalDate.of(2026, 11, 11),
        LocalDate.of(2026, 11, 12),
        LocalDate.of(2026, 11, 13),
        LocalDate.of(2026, 11, 14)
      ),
      dates
    )

    val leapDates = BudgetRecurrenceEngine.generateScheduledDates(
      source = src.copy(startDateIso = "2024-01-01"),
      windowStart = LocalDate.of(2024, 1, 1),
      windowEndExclusive = LocalDate.of(2025, 1, 1)
    )
    assertEquals(366, leapDates.size)
    assertTrue(LocalDate.of(2024, 2, 29) in leapDates)

    val nonLeapDates = BudgetRecurrenceEngine.generateScheduledDates(
      source = src.copy(startDateIso = "2025-01-01"),
      windowStart = LocalDate.of(2025, 1, 1),
      windowEndExclusive = LocalDate.of(2026, 1, 1)
    )
    assertEquals(365, nonLeapDates.size)

    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val todayWindow = BudgetRecurrenceEngine.resolvePeriodWindow(LocalDate.of(2026, 11, 10), profile, listOf(src), 0)
    assertEquals(LocalDate.of(2026, 11, 10), todayWindow.startDate)
    assertEquals(LocalDate.of(2026, 11, 11), todayWindow.endDateExclusive)
  }

  @Test
  fun weeklySchedule_followsAnchorDateByDefaultAndSupportsMondayToSunday() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Weekly Baon",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 150_000L,
      startDateIso = "2026-11-10", // Tuesday
      isPrimary = true
    )

    val anchorProfile = BudgetProfileEntity(
      isConfigured = true,
      weeklyCycleMode = WeeklyCycleMode.ANCHOR_ALIGNED,
      primarySourceId = 1L
    )
    val anchorWin = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 11, 12),
      profile = anchorProfile,
      sources = listOf(src),
      periodOffset = 0
    )
    assertEquals(LocalDate.of(2026, 11, 10), anchorWin.startDate)
    assertEquals(LocalDate.of(2026, 11, 17), anchorWin.endDateExclusive)

    val nextAnchorWin = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 11, 12),
      profile = anchorProfile,
      sources = listOf(src),
      periodOffset = 1
    )
    assertEquals(LocalDate.of(2026, 11, 17), nextAnchorWin.startDate)
    assertEquals(LocalDate.of(2026, 11, 24), nextAnchorWin.endDateExclusive)

    val calendarProfile = anchorProfile.copy(weeklyCycleMode = WeeklyCycleMode.MONDAY_TO_SUNDAY)
    val calWin = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 11, 12),
      profile = calendarProfile,
      sources = listOf(src),
      periodOffset = 0
    )
    assertEquals(LocalDate.of(2026, 11, 9), calWin.startDate)
    assertEquals(LocalDate.of(2026, 11, 16), calWin.endDateExclusive)
  }

  @Test
  fun every14DaysAndEvery21Days_areDistinctFromTwiceMonthlyAndMonthly() {
    val biweeklySrc = IncomeSourceEntity(
      id = 1L,
      name = "Biweekly Allowance",
      frequency = IncomeFrequency.EVERY_14_DAYS,
      expectedAmountCents = 300_000L,
      startDateIso = "2026-01-05",
      isPrimary = true
    )
    val yearOccurrences14 = BudgetRecurrenceEngine.generateScheduledDates(
      source = biweeklySrc,
      windowStart = LocalDate.of(2026, 1, 1),
      windowEndExclusive = LocalDate.of(2027, 1, 1)
    )
    assertEquals(26, yearOccurrences14.size)

    val triweeklySrc = IncomeSourceEntity(
      id = 2L,
      name = "Every 3 Weeks",
      frequency = IncomeFrequency.EVERY_21_DAYS,
      expectedAmountCents = 450_000L,
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val triWindow = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 11, 15),
      profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 2L),
      sources = listOf(triweeklySrc),
      periodOffset = 0
    )
    assertEquals(LocalDate.of(2026, 11, 10), triWindow.startDate)
    assertEquals(LocalDate.of(2026, 12, 1), triWindow.endDateExclusive)
    assertEquals("Every 21 days", triWindow.frequencyLabel)
  }

  @Test
  fun twiceMonthly_produces24AnnualOccurrencesAndRespectsFebAndAllPatterns() {
    val firstAnd15th = IncomeSourceEntity(
      id = 1L,
      name = "1st & 15th Pay",
      frequency = IncomeFrequency.TWICE_MONTHLY,
      expectedAmountCents = 500_000L,
      startDateIso = "2026-01-01",
      twiceMonthlyPattern = TwiceMonthlyPattern.FIRST_AND_FIFTEENTH,
      isPrimary = true
    )
    val dates2026 = BudgetRecurrenceEngine.generateScheduledDates(
      source = firstAnd15th,
      windowStart = LocalDate.of(2026, 1, 1),
      windowEndExclusive = LocalDate.of(2027, 1, 1)
    )
    assertEquals(24, dates2026.size)
    assertEquals(LocalDate.of(2026, 2, 1), dates2026[2])
    assertEquals(LocalDate.of(2026, 2, 15), dates2026[3])

    val fifteenthAndEndLeap = firstAnd15th.copy(
      startDateIso = "2024-02-01",
      twiceMonthlyPattern = TwiceMonthlyPattern.FIFTEENTH_AND_END_OF_MONTH
    )
    val febLeap = BudgetRecurrenceEngine.generateScheduledDates(
      source = fifteenthAndEndLeap,
      windowStart = LocalDate.of(2024, 2, 1),
      windowEndExclusive = LocalDate.of(2024, 3, 1)
    )
    assertEquals(listOf(LocalDate.of(2024, 2, 15), LocalDate.of(2024, 2, 29)), febLeap)

    val customTwoDays = firstAnd15th.copy(
      startDateIso = "2025-02-01",
      twiceMonthlyPattern = TwiceMonthlyPattern.CUSTOM_TWO_DAYS,
      twiceMonthlyDay1 = 5,
      twiceMonthlyDay2 = 31
    )
    val febNonLeap = BudgetRecurrenceEngine.generateScheduledDates(
      source = customTwoDays,
      windowStart = LocalDate.of(2025, 2, 1),
      windowEndExclusive = LocalDate.of(2025, 4, 1)
    )
    assertEquals(
      listOf(
        LocalDate.of(2025, 2, 5),
        LocalDate.of(2025, 2, 28),
        LocalDate.of(2025, 3, 5),
        LocalDate.of(2025, 3, 31)
      ),
      febNonLeap
    )

    // Period stepping across year boundary
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val decFirstHalf = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 12, 10),
      profile = profile,
      sources = listOf(firstAnd15th),
      periodOffset = 0
    )
    assertEquals(LocalDate.of(2026, 12, 1), decFirstHalf.startDate)
    assertEquals(LocalDate.of(2026, 12, 15), decFirstHalf.endDateExclusive)

    val janNextYear = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 12, 10),
      profile = profile,
      sources = listOf(firstAnd15th),
      periodOffset = 2
    )
    assertEquals(LocalDate.of(2027, 1, 1), janNextYear.startDate)
    assertEquals(LocalDate.of(2027, 1, 15), janNextYear.endDateExclusive)
  }

  @Test
  fun monthly_supportsCalendarAndPaydayModesWithDay31ClampingAcrossFebAndYears() {
    val src31 = IncomeSourceEntity(
      id = 1L,
      name = "Monthly 31st Stipend",
      frequency = IncomeFrequency.MONTHLY,
      expectedAmountCents = 800_000L,
      startDateIso = "2024-01-31",
      anchorDayOfMonth = 31,
      isPrimary = true
    )

    val datesLeap = BudgetRecurrenceEngine.generateScheduledDates(
      source = src31,
      windowStart = LocalDate.of(2024, 1, 1),
      windowEndExclusive = LocalDate.of(2024, 5, 1)
    )
    assertEquals(
      listOf(
        LocalDate.of(2024, 1, 31),
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2024, 3, 31),
        LocalDate.of(2024, 4, 30)
      ),
      datesLeap
    )

    val calProfile = BudgetProfileEntity(
      isConfigured = true,
      monthlyCycleMode = MonthlyCycleMode.CALENDAR_MONTH,
      primarySourceId = 1L
    )
    val decToJan = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 12, 15),
      profile = calProfile,
      sources = listOf(src31),
      periodOffset = 1
    )
    assertEquals(LocalDate.of(2027, 1, 1), decToJan.startDate)
    assertEquals(LocalDate.of(2027, 2, 1), decToJan.endDateExclusive)

    val paydayProfile = calProfile.copy(monthlyCycleMode = MonthlyCycleMode.PAYDAY_CYCLE)
    val febPaydayCycle = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2025, 3, 10),
      profile = paydayProfile,
      sources = listOf(src31),
      periodOffset = 0
    )
    assertEquals(LocalDate.of(2025, 2, 28), febPaydayCycle.startDate)
    assertEquals(LocalDate.of(2025, 3, 31), febPaydayCycle.endDateExclusive)
  }

  @Test
  fun customFrequencies_customDaysMonthsDatesAndOneTimeWorkAccurately() {
    val base = IncomeSourceEntity(
      id = 1L,
      name = "Custom",
      frequency = IncomeFrequency.CUSTOM_DAYS,
      expectedAmountCents = 100_000L,
      startDateIso = "2026-11-01"
    )
    for (interval in listOf(5, 10, 18, 25, 45)) {
      val dates = BudgetRecurrenceEngine.generateScheduledDates(
        source = base.copy(customIntervalDays = interval),
        windowStart = LocalDate.of(2026, 11, 1),
        windowEndExclusive = LocalDate.of(2027, 2, 1)
      )
      assertEquals(LocalDate.of(2026, 11, 1), dates.first())
      if (dates.size >= 2) {
        assertEquals(LocalDate.of(2026, 11, 1).plusDays(interval.toLong()), dates[1])
      }
    }

    val every2Months = base.copy(
      frequency = IncomeFrequency.CUSTOM_MONTHS,
      customIntervalMonths = 2,
      startDateIso = "2026-11-15",
      anchorDayOfMonth = 15
    )
    val twoMonthDates = BudgetRecurrenceEngine.generateScheduledDates(
      source = every2Months,
      windowStart = LocalDate.of(2026, 11, 1),
      windowEndExclusive = LocalDate.of(2027, 4, 1)
    )
    assertEquals(
      listOf(
        LocalDate.of(2026, 11, 15),
        LocalDate.of(2027, 1, 15),
        LocalDate.of(2027, 3, 15)
      ),
      twoMonthDates
    )

    val customDates = base.copy(
      frequency = IncomeFrequency.CUSTOM_DATES,
      customDatesCsv = "2026-11-12, 2026-11-28, 2026-12-05"
    )
    assertEquals(
      listOf(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 28)),
      BudgetRecurrenceEngine.generateScheduledDates(
        customDates,
        LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 12, 1)
      )
    )

    val oneTime = base.copy(
      frequency = IncomeFrequency.ONE_TIME,
      startDateIso = "2026-11-20"
    )
    assertEquals(
      listOf(LocalDate.of(2026, 11, 20)),
      BudgetRecurrenceEngine.generateScheduledDates(
        oneTime,
        LocalDate.of(2026, 11, 1),
        LocalDate.of(2027, 1, 1)
      )
    )
  }

  @Test
  fun multipleSourcesPausedExpiredEffectiveFromAndVariableOccurrences_resolveAccurately() {
    val activeMonthly = IncomeSourceEntity(
      id = 1L,
      name = "Scholarship",
      frequency = IncomeFrequency.MONTHLY,
      expectedAmountCents = 800_000L,
      startDateIso = "2026-11-01",
      isPrimary = true
    )
    val sideGigPausedFromNov16 = IncomeSourceEntity(
      id = 2L,
      name = "Tutoring",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 100_000L,
      isVariableAmount = true,
      startDateIso = "2026-11-02",
      isPaused = true,
      pausedFromDateIso = "2026-11-16"
    )
    val expiredShortContract = IncomeSourceEntity(
      id = 3L,
      name = "Part-time",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 50_000L,
      startDateIso = "2026-11-05",
      endDateIso = "2026-11-12"
    )
    val variableConfirmedOcc = IncomeOccurrenceEntity(
      id = 10L,
      sourceId = 2L,
      sourceNameSnapshot = "Tutoring",
      expectedDateIso = "2026-11-09",
      expectedAmountCents = 100_000L,
      actualReceivedAmountCents = 135_000L,
      actualReceivedDateIso = "2026-11-09",
      status = IncomeOccurrenceStatus.RECEIVED
    )

    val novWindow = BudgetPeriodWindow(
      periodOffset = 0,
      startDate = LocalDate.of(2026, 11, 1),
      endDateExclusive = LocalDate.of(2026, 12, 1),
      label = "November 2026",
      frequencyLabel = "Monthly"
    )
    val resolved = BudgetRecurrenceEngine.resolveOccurrencesForWindow(
      sources = listOf(activeMonthly, sideGigPausedFromNov16, expiredShortContract),
      persistedOccurrences = listOf(variableConfirmedOcc),
      window = novWindow,
      primarySourceId = 1L
    )

    // Scholarship: Nov 1
    // Tutoring (paused from Nov 16): Nov 2 (template), Nov 9 (confirmed variable ₱1,350) -> Nov 16/23/30 paused!
    // Part-time (ends Nov 12): Nov 5, Nov 12 -> Nov 19/26 expired!
    val datesBySource = resolved.groupBy({ it.sourceId }, { it.expectedDate })
    assertEquals(listOf(LocalDate.of(2026, 11, 1)), datesBySource[1L])
    assertEquals(listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9)), datesBySource[2L])
    assertEquals(listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12)), datesBySource[3L])

    val nov9Occ = resolved.single { it.sourceId == 2L && it.expectedDate == LocalDate.of(2026, 11, 9) }
    assertEquals(135_000L, nov9Occ.actualReceivedAmountCents)
    assertTrue(nov9Occ.isVariableAmount)

    // EffectiveFrom cutover omits dates before effectiveFrom unless persisted
    val editedSource = activeMonthly.copy(effectiveFromDateIso = "2026-12-01")
    assertTrue(
      BudgetRecurrenceEngine.generateScheduledDates(
        source = editedSource,
        windowStart = LocalDate.of(2026, 11, 1),
        windowEndExclusive = LocalDate.of(2026, 12, 1)
      ).isEmpty()
    )
  }

  @Test
  fun frequencyNormalization_usesExactBigDecimalAnnualMultipliers() {
    val weekly = IncomeSourceEntity(
      id = 1L,
      name = "Weekly",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 100_000L,
      startDateIso = "2026-01-01"
    )
    val biweekly = IncomeSourceEntity(
      id = 2L,
      name = "Every 14d",
      frequency = IncomeFrequency.EVERY_14_DAYS,
      expectedAmountCents = 100_000L,
      startDateIso = "2026-01-01"
    )
    val twiceMonthly = IncomeSourceEntity(
      id = 3L,
      name = "Twice Monthly",
      frequency = IncomeFrequency.TWICE_MONTHLY,
      expectedAmountCents = 100_000L,
      startDateIso = "2026-01-01"
    )
    val monthly = IncomeSourceEntity(
      id = 4L,
      name = "Monthly",
      frequency = IncomeFrequency.MONTHLY,
      expectedAmountCents = 800_000L,
      startDateIso = "2026-01-01"
    )

    val norm = BudgetRecurrenceEngine.calculateFrequencyNormalization(
      sources = listOf(weekly, biweekly, twiceMonthly, monthly),
      referenceYear = 2026
    )

    val byId = norm.items.associateBy { it.sourceId }
    assertEquals(5_200_000L, byId[1L]!!.normalizedAnnualCents)
    assertEquals(433_333L, byId[1L]!!.normalizedMonthlyCents)

    assertEquals(2_600_000L, byId[2L]!!.normalizedAnnualCents)
    assertEquals(216_667L, byId[2L]!!.normalizedMonthlyCents)

    assertEquals(2_400_000L, byId[3L]!!.normalizedAnnualCents)
    assertEquals(200_000L, byId[3L]!!.normalizedMonthlyCents)

    assertEquals(9_600_000L, byId[4L]!!.normalizedAnnualCents)
    assertEquals(800_000L, byId[4L]!!.normalizedMonthlyCents)
  }
}
