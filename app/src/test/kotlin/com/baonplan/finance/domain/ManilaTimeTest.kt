package com.baonplan.finance.domain

import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ManilaTimeTest {

  @Test
  fun weekBounds_computeMondayToSundayCorrectly() {
    val mondayNov9 = LocalDate.of(2026, 11, 9)
    val wednesdayNov11 = LocalDate.of(2026, 11, 11)
    val sundayNov15 = LocalDate.of(2026, 11, 15)

    assertEquals(mondayNov9, ManilaTime.weekStart(wednesdayNov11))
    assertEquals(sundayNov15, ManilaTime.weekEnd(wednesdayNov11))
    assertTrue(ManilaTime.isInWeek("2026-11-09", mondayNov9))
    assertTrue(ManilaTime.isInWeek("2026-11-15", mondayNov9))
    assertFalse(ManilaTime.isInWeek("2026-11-16", mondayNov9))
  }

  @Test
  fun classifyUrgency_identifiesOverdueTodayTomorrowUpcomingUnconfirmedUnknown() {
    val today = LocalDate.of(2026, 11, 9)
    assertEquals(
      PaymentUrgency.OVERDUE,
      ManilaTime.classifyUrgency(today, "2026-11-08", DueDateStatus.CONFIRMED)
    )
    assertEquals(
      PaymentUrgency.DUE_TODAY,
      ManilaTime.classifyUrgency(today, "2026-11-09", DueDateStatus.CONFIRMED)
    )
    assertEquals(
      PaymentUrgency.DUE_TOMORROW,
      ManilaTime.classifyUrgency(today, "2026-11-10", DueDateStatus.CONFIRMED)
    )
    assertEquals(
      PaymentUrgency.UPCOMING,
      ManilaTime.classifyUrgency(today, "2026-11-16", DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
    )
    assertEquals(
      PaymentUrgency.UNCONFIRMED,
      ManilaTime.classifyUrgency(today, null, DueDateStatus.UNCONFIRMED)
    )
    assertEquals(
      PaymentUrgency.UNKNOWN,
      ManilaTime.classifyUrgency(today, null, DueDateStatus.UNKNOWN)
    )
  }

  @Test
  fun advanceDate_neverAssumesUnknownOrOneTimeFrequency() {
    val base = LocalDate.of(2026, 11, 9)
    assertEquals(LocalDate.of(2026, 11, 16), ManilaTime.advanceDate(base, RepaymentFrequency.WEEKLY))
    assertEquals(LocalDate.of(2026, 11, 23), ManilaTime.advanceDate(base, RepaymentFrequency.EVERY_14_DAYS))
    assertEquals(LocalDate.of(2026, 12, 9), ManilaTime.advanceDate(base, RepaymentFrequency.MONTHLY))
    assertEquals(LocalDate.of(2026, 11, 19), ManilaTime.advanceDate(base, RepaymentFrequency.CUSTOM_DAYS, 10))
    assertNull(ManilaTime.advanceDate(base, RepaymentFrequency.UNKNOWN))
    assertNull(ManilaTime.advanceDate(base, RepaymentFrequency.ONE_TIME))
    assertNull(ManilaTime.advanceDate(base, RepaymentFrequency.CUSTOM_DAYS, null))
  }

  @Test
  fun formatDaysUntilDue_formatsFutureTodayTomorrowAndOverdue() {
    val today = LocalDate.of(2026, 11, 10)
    assertEquals("Due today", ManilaTime.formatDaysUntilDue("2026-11-10", today))
    assertEquals("Tomorrow", ManilaTime.formatDaysUntilDue("2026-11-11", today))
    assertEquals("In 13 days", ManilaTime.formatDaysUntilDue("2026-11-23", today))
    assertEquals("1 day overdue", ManilaTime.formatDaysUntilDue("2026-11-09", today))
    assertEquals("5 days overdue", ManilaTime.formatDaysUntilDue("2026-11-05", today))
    assertNull(ManilaTime.formatDaysUntilDue(null, today))
  }

  @Test
  fun philippineHolidays_shiftBonifacioDayAndSnapBackToMondayAnchor() {
    assertTrue(ManilaTime.isPhilippineRegularHoliday(LocalDate.of(2026, 11, 30)))
    assertEquals(
      LocalDate.of(2026, 12, 1),
      ManilaTime.adjustForPhilippineHoliday(LocalDate.of(2026, 11, 30))
    )
    assertEquals(
      LocalDate.of(2026, 11, 30),
      ManilaTime.inferUnshiftedAnchorDate(LocalDate.of(2026, 12, 1))
    )

    // Weekly from Nov 23 (21 installments -> #24 is Apr 12, 2027), shifting Nov 30 -> Dec 01 then snapping back Dec 07
    val weekly21 = ManilaTime.generateProjectedScheduleDates(
      nextDueDate = LocalDate.of(2026, 11, 23),
      count = 21,
      frequency = RepaymentFrequency.WEEKLY,
      adjustHolidays = true
    )
    assertEquals(21, weekly21.size)
    assertEquals(LocalDate.of(2026, 11, 23), weekly21[0])
    assertEquals(LocalDate.of(2026, 12, 1), weekly21[1])
    assertEquals(LocalDate.of(2026, 12, 7), weekly21[2])
    assertEquals(LocalDate.of(2027, 4, 12), weekly21.last())

    // Every 14 days starting on shifted Dec 01 recovers Nov 30 anchor -> Dec 14 -> Dec 28
    val biweekly3 = ManilaTime.generateProjectedScheduleDates(
      nextDueDate = LocalDate.of(2026, 12, 1),
      count = 3,
      frequency = RepaymentFrequency.EVERY_14_DAYS,
      adjustHolidays = true
    )
    assertEquals(
      listOf(
        LocalDate.of(2026, 12, 1),
        LocalDate.of(2026, 12, 14),
        LocalDate.of(2026, 12, 28)
      ),
      biweekly3
    )
  }
}
