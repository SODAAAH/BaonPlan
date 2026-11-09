package com.baonplan.finance.domain

import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FinancialCalculatorTest {

  private fun sampleTestDebts(): List<DebtEntity> = listOf(
    DebtEntity(
      id = 1L,
      name = "Biweekly Wallet Loan",
      lender = "Lender A",
      category = "E-Wallet",
      remainingBalanceCents = 33_507L,
      installmentAmountCents = 11_169L,
      remainingInstallmentsCount = 3,
      frequency = RepaymentFrequency.EVERY_14_DAYS,
      dateStatus = DueDateStatus.UNCONFIRMED,
      status = DebtStatus.ACTIVE
    ),
    DebtEntity(
      id = 2L,
      name = "Weekly Cash Loan",
      lender = "Lender B",
      category = "Cash Loan",
      remainingBalanceCents = 44_455L,
      installmentAmountCents = 2_615L,
      remainingInstallmentsCount = 17,
      frequency = RepaymentFrequency.WEEKLY,
      lastKnownPaidDate = "2026-11-09",
      nextDueDate = "2026-11-16",
      dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
      status = DebtStatus.ACTIVE
    ),
    DebtEntity(
      id = 3L,
      name = "Unknown Schedule Loan",
      lender = "Lender A",
      category = "Cash Loan",
      remainingBalanceCents = 108_738L,
      installmentAmountCents = 5_178L,
      remainingInstallmentsCount = 21,
      frequency = RepaymentFrequency.UNKNOWN,
      dateStatus = DueDateStatus.UNKNOWN,
      status = DebtStatus.ACTIVE
    ),
    DebtEntity(
      id = 4L,
      name = "Upcoming BNPL",
      lender = "BNPL",
      category = "BNPL",
      totalScheduledRepaymentCents = 22_917L,
      remainingBalanceCents = 22_917L,
      installmentAmountCents = 22_917L,
      remainingInstallmentsCount = 1,
      frequency = RepaymentFrequency.ONE_TIME,
      nextDueDate = "2026-12-15",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE
    ),
    DebtEntity(
      id = 5L,
      name = "Paid Off Wallet Loan",
      lender = "Lender A",
      category = "E-Wallet",
      originalPrincipalCents = 50_000L,
      totalScheduledRepaymentCents = 50_000L,
      priorPaidCents = 50_000L,
      isPriorPaidKnown = true,
      remainingBalanceCents = 0L,
      status = DebtStatus.PAID
    )
  )

  private fun sampleTestInstallments(): List<InstallmentEntity> = listOf(
    InstallmentEntity(
      id = 1L,
      debtId = 2L,
      installmentNumber = 1,
      dueDate = "2026-11-16",
      amountDueCents = 2_615L,
      dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
      status = InstallmentStatus.UPCOMING
    ),
    InstallmentEntity(
      id = 2L,
      debtId = 4L,
      installmentNumber = 1,
      dueDate = "2026-12-15",
      amountDueCents = 22_917L,
      dateStatus = DueDateStatus.CONFIRMED,
      status = InstallmentStatus.UPCOMING
    )
  )

  @Test
  fun activeDebtsAndProgress_computeExactCentavoTotals() {
    val debts = sampleTestDebts()
    val totalActive = FinancialCalculator.calculateTotalActiveDebt(debts)
    assertEquals(209_617L, totalActive) // ₱2,096.17

    val progress = FinancialCalculator.calculateRepaymentProgress(debts)
    assertEquals(4, progress.activeLoansCount)
    assertEquals(1, progress.completedLoansCount)
    assertEquals(50_000L, progress.completedLoansOriginalTotalCents)
  }

  @Test
  fun allowanceScenarios_match400And500FoodAndSafeBufferRules() {
    val foodBudget = FinancialCalculator.calculateWeeklyFoodBudget(
      schoolDaysPerWeek = 2,
      foodPerSchoolDayCents = 5_000L
    )
    assertEquals(10_000L, foodBudget) // ₱100.00

    val plan400 = FinancialCalculator.calculateScenarioPlan(
      scenarioName = "₱400",
      allowanceCents = 40_000L,
      foodBudgetCents = foodBudget,
      otherExpensesCents = 5_000L,
      plannedDebtReserveCents = 15_000L,
      emergencySavingsCents = 5_000L
    )
    assertEquals(30_000L, plan400.remainingAfterFoodCents) // ₱300.00
    assertEquals(5_000L, plan400.remainingSpendingMoneyCents)
    assertTrue(plan400.recommendedSafeDebtReserveCents < plan400.remainingAfterFoodCents)

    val plan500 = FinancialCalculator.calculateScenarioPlan(
      scenarioName = "₱500",
      allowanceCents = 50_000L,
      foodBudgetCents = foodBudget,
      otherExpensesCents = 5_000L,
      plannedDebtReserveCents = 15_000L,
      emergencySavingsCents = 5_000L
    )
    assertEquals(40_000L, plan500.remainingAfterFoodCents) // ₱400.00
    assertEquals(15_000L, plan500.remainingSpendingMoneyCents)
    assertTrue(plan500.recommendedSafeDebtReserveCents < plan500.remainingAfterFoodCents)
  }

  @Test
  fun weeklyBudgetSummary_doesNotDoubleSubtractReserveFundedRepayments() {
    val today = LocalDate.of(2026, 11, 9)
    val settings = DefaultSettingsProvider.defaultSettings()
    val allowances = listOf(
      AllowanceRecordEntity(
        id = 1L,
        weekStartDate = "2026-11-09",
        receivedDate = "2026-11-09",
        amountCents = 40_000L
      )
    )
    val expenses = listOf(
      ExpenseEntity(
        id = 1L,
        date = "2026-11-10",
        category = ExpenseCategory.SCHOOL_FOOD,
        title = "School Lunch",
        amountCents = 5_000L,
        isSchoolDay = true
      ),
      ExpenseEntity(
        id = 2L,
        date = "2026-11-12",
        category = ExpenseCategory.SCHOOL_FOOD,
        title = "School Lunch",
        amountCents = 5_000L,
        isSchoolDay = true
      )
    )
    val reserves = listOf(
      DebtReserveEntity(
        id = 1L,
        weekStartDate = "2026-11-09",
        reserveType = ReserveType.DEBT_REPAYMENT_RESERVE,
        amountCents = 15_000L,
        appliedAmountCents = 2_615L,
        status = ReserveStatus.RESERVED
      ),
      DebtReserveEntity(
        id = 2L,
        weekStartDate = "2026-11-09",
        reserveType = ReserveType.EMERGENCY_SAVINGS,
        amountCents = 5_000L,
        status = ReserveStatus.RESERVED
      )
    )
    val txs = listOf(
      TransactionEntity(
        id = 1L,
        type = TransactionType.DEBT_REPAYMENT,
        title = "Weekly Loan Payment",
        amountCents = 2_615L,
        date = "2026-11-11",
        debtId = 2L,
        fundedFromReserveCents = 2_615L
      )
    )

    val summary = FinancialCalculator.calculateWeeklyBudgetSummary(
      today = today,
      settings = settings,
      allowances = allowances,
      expenses = expenses,
      reserves = reserves,
      transactions = txs
    )

    assertEquals(40_000L, summary.recordedAllowanceCents)
    assertEquals(10_000L, summary.actualFoodExpensesCents)
    assertEquals(12_385L, summary.activeDebtReserveCents)
    assertEquals(5_000L, summary.emergencySavingsThisWeekCents)
    assertEquals(2_615L, summary.repaymentsPaidThisWeekCents)
    assertEquals(0L, summary.directRepaymentsFromCashCents)
    assertEquals(10_000L, summary.availableSpendingMoneyCents)
  }

  @Test
  fun paymentOverview_separatesConfirmedFromEstimatedAndUnconfirmed() {
    val today = LocalDate.of(2026, 11, 9)
    val debts = sampleTestDebts()
    val installments = sampleTestInstallments()

    val overview = FinancialCalculator.buildPaymentOverview(debts, installments, today)

    val confirmed = overview.nextConfirmedPayment
    assertNotNull(confirmed)
    assertEquals("Upcoming BNPL", confirmed!!.debtName)
    assertEquals("2026-12-15", confirmed.dueDateIso)

    val estimated = overview.nextEstimatedPayment
    assertNotNull(estimated)
    assertEquals("Weekly Cash Loan", estimated!!.debtName)
    assertEquals("2026-11-16", estimated.dueDateIso)

    assertEquals(2, overview.unconfirmedOrUnknownDebts.size)
  }

  @Test
  fun generateInstallmentSchedule_refusesUnknownFrequency() {
    val unknown = FinancialCalculator.generateInstallmentSchedule(
      startDate = LocalDate.of(2026, 11, 16),
      count = 5,
      installmentAmountCents = 5_178L,
      totalRemainingCents = 25_890L,
      frequency = RepaymentFrequency.UNKNOWN,
      dateStatus = DueDateStatus.UNCONFIRMED
    )
    assertTrue(unknown.isEmpty())

    val biweekly = FinancialCalculator.generateInstallmentSchedule(
      startDate = LocalDate.of(2026, 11, 16),
      count = 3,
      installmentAmountCents = 11_169L,
      totalRemainingCents = 33_507L,
      frequency = RepaymentFrequency.EVERY_14_DAYS,
      dateStatus = DueDateStatus.CONFIRMED
    )
    assertEquals(3, biweekly.size)
    assertEquals("2026-11-16", biweekly[0].dueDateIso)
    assertEquals("2026-11-30", biweekly[1].dueDateIso)
    assertEquals("2026-12-14", biweekly[2].dueDateIso)
    assertEquals(33_507L, biweekly.sumOf { it.amountDueCents })
  }

  @Test
  fun loanCostBreakdown_distinguishesPrincipalReceivedInterestAndFees() {
    val debt = DebtEntity(
      id = 99L,
      name = "Test Loan",
      lender = "Test",
      category = "Cash",
      originalPrincipalCents = 100_000L,
      amountReceivedCents = 92_000L,
      interestCents = 12_000L,
      serviceFeeCents = 5_000L,
      processingFeeCents = 3_000L,
      totalScheduledRepaymentCents = null,
      priorPaidCents = 20_000L,
      isPriorPaidKnown = true,
      trackedPaidCents = 10_000L,
      remainingBalanceCents = 90_000L
    )
    val cost = FinancialCalculator.calculateLoanCostBreakdown(debt)
    assertEquals(100_000L, cost.originalPrincipalCents)
    assertEquals(92_000L, cost.amountReceivedCents)
    assertEquals(20_000L, cost.totalFeesAndInterestCents)
    assertEquals(120_000L, cost.totalScheduledRepaymentCents)
    assertEquals(30_000L, cost.totalKnownPaidCents)
    assertEquals(90_000L, cost.remainingBalanceCents)
  }
}
