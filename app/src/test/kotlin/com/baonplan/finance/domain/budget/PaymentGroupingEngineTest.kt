package com.baonplan.finance.domain.budget

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaymentGroupingEngineTest {

  private fun debt(id: Long, name: String, lender: String = name, balanceCents: Long = 100_000L) =
    DebtEntity(
      id = id,
      name = name,
      lender = lender,
      applicationProvider = lender,
      category = "Loan",
      remainingBalanceCents = balanceCents,
      installmentAmountCents = 50_000L,
      remainingInstallmentsCount = 1,
      frequency = RepaymentFrequency.MONTHLY,
      nextDueDate = "2026-11-16",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE
    )

  private fun inst(
    id: Long,
    debtId: Long,
    dueDateIso: String?,
    amountDueCents: Long,
    paidAmountCents: Long = 0L,
    status: InstallmentStatus = InstallmentStatus.UPCOMING,
    dateStatus: DueDateStatus = DueDateStatus.CONFIRMED
  ) = InstallmentEntity(
    id = id,
    debtId = debtId,
    installmentNumber = id.toInt(),
    dueDate = dueDateIso,
    amountDueCents = amountDueCents,
    paidAmountCents = paidAmountCents,
    dateStatus = dateStatus,
    status = status
  )

  @Test
  fun sameDatePayments_group2_4_10ItemsIntoSingleDateGroup() {
    for (count in listOf(2, 4, 10)) {
      val debts = (1..count).map { idx -> debt(idx.toLong(), "Loan $idx") }
      val installments = (1..count).map { idx -> inst(idx.toLong(), idx.toLong(), "2026-11-16", 10_000L) }

      val grouped = PaymentGroupingEngine.buildGroupedPayments(
        debts = debts,
        installments = installments,
        today = LocalDate.of(2026, 11, 10)
      )

      assertNotNull(grouped.nextGroup)
      val next = grouped.nextGroup!!
      assertTrue(next.isSingleDate)
      assertEquals("Nov 16", next.dateRangeLabel)
      assertEquals(count, next.totalPaymentsCount)
      assertEquals(count, next.distinctLoansCount)
      assertEquals(count * 10_000L, next.knownTotalCents)
      assertTrue(grouped.followingGroups.isEmpty())
    }
  }

  @Test
  fun threeDayWindow_groupsOffsets0Through3AndSeparatesDay4() {
    val debts = (1..5).map { idx -> debt(idx.toLong(), "Loan $idx") }
    val installments = listOf(
      inst(1L, 1L, "2026-11-16", 10_000L),
      inst(2L, 2L, "2026-11-17", 10_000L),
      inst(3L, 3L, "2026-11-18", 10_000L),
      inst(4L, 4L, "2026-11-19", 10_000L),
      inst(5L, 5L, "2026-11-20", 25_000L) // +4 days -> separate group
    )

    val grouped = PaymentGroupingEngine.buildGroupedPayments(
      debts = debts,
      installments = installments,
      today = LocalDate.of(2026, 11, 10)
    )

    val next = grouped.nextGroup!!
    assertFalse(next.isSingleDate)
    assertEquals("Nov 16–19", next.dateRangeLabel)
    assertEquals(4, next.totalPaymentsCount)
    assertEquals(40_000L, next.knownTotalCents)

    val following = grouped.followingGroups.single()
    assertTrue(following.isSingleDate)
    assertEquals("Nov 20", following.dateRangeLabel)
    assertEquals(25_000L, following.knownTotalCents)
  }

  @Test
  fun antiChainedGrouping_neverExtendsAnchorWindowPastThreeDaysFromGroupAnchor() {
    val debts = listOf(debt(1L, "A"), debt(2L, "B"), debt(3L, "C"))
    val installments = listOf(
      inst(1L, 1L, "2026-11-16", 10_000L),
      inst(2L, 2L, "2026-11-19", 20_000L),
      inst(3L, 3L, "2026-11-22", 30_000L) // 3 days after Nov 19, NOT 3 days after Nov 16!
    )

    val grouped = PaymentGroupingEngine.buildGroupedPayments(
      debts = debts,
      installments = installments,
      today = LocalDate.of(2026, 11, 10)
    )

    val next = grouped.nextGroup!!
    assertEquals("Nov 16–19", next.dateRangeLabel)
    assertEquals(30_000L, next.knownTotalCents)
    assertEquals(1, grouped.followingGroups.size)
    assertEquals("Nov 22", grouped.followingGroups.single().dateRangeLabel)
    assertEquals(30_000L, grouped.followingGroups.single().knownTotalCents)
  }

  @Test
  fun regression_phMultiProviderAggregation_matchesPart25FExactTotalsAndDates() {
    val debts = listOf(
      debt(1L, "GCash Cash Loan", "GCash (GLoan)"),
      debt(2L, "BillEase Loan", "BillEase"),
      debt(3L, "Maya Loan", "Maya (Easy Credit)"),
      debt(4L, "Shopee SLoan", "Shopee SLoan"),
      debt(5L, "Atome Cash Loan", "Atome")
    )
    val installments = listOf(
      inst(11L, 1L, "2026-11-16", 30_000L),
      inst(12L, 2L, "2026-11-16", 45_000L),
      inst(13L, 3L, "2026-11-18", 35_000L),
      inst(14L, 4L, "2026-11-19", 25_000L),
      inst(15L, 5L, "2026-11-25", 50_000L)
    )

    val grouped = PaymentGroupingEngine.buildGroupedPayments(
      debts = debts,
      installments = installments,
      today = LocalDate.of(2026, 11, 10)
    )

    val g1 = grouped.nextGroup!!
    assertEquals("Nov 16–19", g1.dateRangeLabel)
    assertEquals(4, g1.totalPaymentsCount)
    assertEquals(4, g1.distinctLoansCount)
    assertEquals(135_000L, g1.knownTotalCents) // ₱1,350
    // Individual due dates preserved exactly
    assertEquals(
      listOf(
        LocalDate.of(2026, 11, 16),
        LocalDate.of(2026, 11, 16),
        LocalDate.of(2026, 11, 18),
        LocalDate.of(2026, 11, 19)
      ),
      g1.items.map { it.dueDate }
    )

    val g2 = grouped.followingGroups.single()
    assertEquals("Nov 25", g2.dateRangeLabel)
    assertEquals(1, g2.totalPaymentsCount)
    assertEquals(50_000L, g2.knownTotalCents) // ₱500
  }

  @Test
  fun providerIndependence_groupsAllPhilippineAndGenericProvidersStrictlyByDate() {
    val providers = listOf(
      "GCash", "BillEase", "Maya", "Shopee", "Atome",
      "CIMB", "Home Credit", "Skyro", "Tala", "Local Coop Loan"
    )
    val debts = providers.mapIndexed { i, p -> debt((i + 1).toLong(), "$p Loan", p) }
    val installments = providers.mapIndexed { i, _ ->
      inst((i + 1).toLong(), (i + 1).toLong(), "2026-11-18", 10_000L)
    }

    val grouped = PaymentGroupingEngine.buildGroupedPayments(
      debts = debts,
      installments = installments,
      today = LocalDate.of(2026, 11, 15)
    )

    val g = grouped.nextGroup!!
    assertEquals(10, g.totalPaymentsCount)
    assertEquals(10, g.distinctLoansCount)
    assertEquals(100_000L, g.knownTotalCents)
    assertEquals("Due in 3 days", g.countdownLabel)
  }

  @Test
  fun statuses_paidExcludedPartialRemainingOverdueSeparatedPendingFlaggedUnknownHonest() {
    val debts = listOf(
      debt(1L, "Paid Loan", balanceCents = 50_000L),
      debt(2L, "Partial Loan"),
      debt(3L, "Overdue Loan"),
      debt(4L, "Pending Loan"),
      debt(5L, "Unknown Amount Loan"),
      debt(6L, "Undated Loan")
    )
    val installments = listOf(
      inst(11L, 1L, "2026-11-12", 50_000L, paidAmountCents = 50_000L, status = InstallmentStatus.PAID),
      inst(12L, 1L, "2026-11-13", 50_000L, status = InstallmentStatus.REVERSED),
      inst(21L, 2L, "2026-11-12", 50_000L, paidAmountCents = 30_000L, status = InstallmentStatus.PARTIAL),
      inst(31L, 3L, "2026-11-08", 40_000L, status = InstallmentStatus.OVERDUE),
      inst(41L, 4L, "2026-11-12", 15_000L, status = InstallmentStatus.PENDING),
      inst(51L, 5L, "2026-11-14", 0L, status = InstallmentStatus.UPCOMING),
      inst(61L, 6L, null, 25_000L, status = InstallmentStatus.UPCOMING, dateStatus = DueDateStatus.UNCONFIRMED)
    )

    val grouped = PaymentGroupingEngine.buildGroupedPayments(
      debts = debts,
      installments = installments,
      today = LocalDate.of(2026, 11, 10)
    )

    val overdue = grouped.overdueGroup!!
    assertTrue(overdue.isOverdueGroup)
    assertEquals(40_000L, overdue.knownTotalCents)
    assertEquals("Overdue by 2 days", overdue.countdownLabel)

    val next = grouped.nextGroup!!
    assertEquals("Nov 12–14", next.dateRangeLabel)
    assertEquals(3, next.totalPaymentsCount) // Partial + Pending + UnknownAmt (PAID/REVERSED excluded)
    assertEquals(35_000L, next.knownTotalCents) // 20k partial remaining + 15k pending
    assertTrue(next.hasPendingPayments)
    assertTrue(next.hasIncompleteAmounts)
    val unknownItem = next.items.single { it.debtId == 5L }
    assertNull(unknownItem.unpaidAmountCents)
    assertFalse(unknownItem.isAmountKnown)

    assertEquals(1, grouped.undatedOrUnconfirmedPayments.size)
    assertEquals(6L, grouped.undatedOrUnconfirmedPayments.single().debtId)
  }

  @Test
  fun overdueOnlyScenario_keepsOverdueVisibleWhenNoUpcomingDatesExist() {
    val grouped = PaymentGroupingEngine.buildGroupedPayments(
      debts = listOf(debt(1L, "Overdue Only")),
      installments = listOf(inst(1L, 1L, "2026-11-05", 30_000L, status = InstallmentStatus.UPCOMING)),
      today = LocalDate.of(2026, 11, 10)
    )

    assertNotNull(grouped.overdueGroup)
    assertNull(grouped.nextGroup)
    assertTrue(grouped.hasAnyActionablePayments)
    assertEquals("Overdue by 5 days", grouped.overdueGroup!!.countdownLabel)
  }

  @Test
  fun dateFormattingAndLeapYearCalendarBoundaries_handleCrossMonthCrossYearAndFeb29() {
    val crossMonth = PaymentGroupingEngine.buildGroupedPayments(
      debts = listOf(debt(1L, "A"), debt(2L, "B")),
      installments = listOf(
        inst(1L, 1L, "2026-11-29", 10_000L),
        inst(2L, 2L, "2026-12-02", 10_000L) // +3 days across month end
      ),
      today = LocalDate.of(2026, 11, 29)
    )
    val crossNext = crossMonth.nextGroup!!
    assertEquals("Nov 29–Dec 2", crossNext.dateRangeLabel)
    assertEquals("Due today – Dec 2", crossNext.countdownLabel)

    val crossYear = PaymentGroupingEngine.buildGroupedPayments(
      debts = listOf(debt(1L, "A"), debt(2L, "B")),
      installments = listOf(
        inst(1L, 1L, "2026-12-30", 10_000L),
        inst(2L, 2L, "2027-01-02", 10_000L) // +3 days across year end
      ),
      today = LocalDate.of(2026, 12, 29)
    )
    val yearNext = crossYear.nextGroup!!
    assertEquals("Dec 30, 2026–Jan 2, 2027", yearNext.dateRangeLabel)
    assertEquals("Starts tomorrow", yearNext.countdownLabel)

    val leapYearFeb = PaymentGroupingEngine.buildGroupedPayments(
      debts = listOf(debt(1L, "A"), debt(2L, "B")),
      installments = listOf(
        inst(1L, 1L, "2024-02-28", 10_000L),
        inst(2L, 2L, "2024-03-02", 10_000L) // 2024 has Feb 29 -> +3 days inclusive!
      ),
      today = LocalDate.of(2024, 2, 27)
    )
    assertEquals(2, leapYearFeb.nextGroup!!.totalPaymentsCount)
  }
}
