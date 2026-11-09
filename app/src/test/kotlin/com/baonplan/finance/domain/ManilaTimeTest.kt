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
}
