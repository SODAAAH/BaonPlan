package com.baonplan.finance.domain.budget

import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.BudgetAllocationEntity
import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.IncomeOccurrenceEntity
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.ObligationRole
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CashFlowBudgetEngineTest {

  private fun receivedOccurrence(
    id: Long,
    sourceId: Long,
    dateIso: String,
    amountCents: Long,
    expectedAmountCents: Long = amountCents,
    status: IncomeOccurrenceStatus = IncomeOccurrenceStatus.RECEIVED
  ) = IncomeOccurrenceEntity(
    id = id,
    sourceId = sourceId,
    sourceNameSnapshot = "Allowance",
    expectedDateIso = dateIso,
    expectedAmountCents = expectedAmountCents,
    actualReceivedAmountCents = if (status.isSettledCashIn) amountCents else 0L,
    actualReceivedDateIso = if (status.isSettledCashIn) dateIso else null,
    status = status
  )

  private fun debt(
    id: Long,
    name: String,
    balanceCents: Long,
    instCents: Long = balanceCents,
    status: DebtStatus = DebtStatus.ACTIVE,
    classification: LoanProductClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
    obligationRole: ObligationRole = ObligationRole.STANDALONE,
    scheduleVersion: Int = 1,
    minimumDueCents: Long? = null,
    creditLimitCents: Long? = null,
    nextDueDate: String? = "2026-11-16",
    dateStatus: DueDateStatus = DueDateStatus.CONFIRMED
  ) = DebtEntity(
    id = id,
    name = name,
    lender = name,
    category = "Loan",
    productClassification = classification,
    obligationRole = obligationRole,
    scheduleVersion = scheduleVersion,
    minimumDueCents = minimumDueCents,
    creditLimitCents = creditLimitCents,
    remainingBalanceCents = balanceCents,
    installmentAmountCents = instCents,
    remainingInstallmentsCount = 1,
    frequency = RepaymentFrequency.MONTHLY,
    nextDueDate = nextDueDate,
    dateStatus = dateStatus,
    status = status
  )

  private fun inst(
    id: Long,
    debtId: Long,
    dueDateIso: String?,
    amountDueCents: Long,
    paidAmountCents: Long = 0L,
    status: InstallmentStatus = InstallmentStatus.UPCOMING,
    dateStatus: DueDateStatus = DueDateStatus.CONFIRMED,
    scheduleVersion: Int = 1
  ) = InstallmentEntity(
    id = id,
    debtId = debtId,
    installmentNumber = id.toInt(),
    dueDate = dueDateIso,
    amountDueCents = amountDueCents,
    paidAmountCents = paidAmountCents,
    dateStatus = dateStatus,
    status = status,
    scheduleVersion = scheduleVersion
  )

  // --- PART 25E MANDATORY REGRESSION SUITE ---

  @Test
  fun regression_monthlyBudget_deductsAllFourDueLoansFromMonthlyAllowance() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Monthly Allowance",
      frequency = IncomeFrequency.MONTHLY,
      expectedAmountCents = 800_000L, // ₱8,000
      startDateIso = "2026-11-01",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(
      isConfigured = true,
      monthlyCycleMode = MonthlyCycleMode.CALENDAR_MONTH,
      primarySourceId = 1L
    )
    val debts = listOf(
      debt(1L, "GCash Cash Loan", 50_000L),
      debt(2L, "BillEase Loan", 70_000L),
      debt(3L, "Maya Loan", 35_000L),
      debt(4L, "Shopee SLoan", 45_000L)
    )
    val installments = listOf(
      inst(11L, 1L, "2026-11-12", 50_000L),
      inst(12L, 2L, "2026-11-16", 70_000L),
      inst(13L, 3L, "2026-11-20", 35_000L),
      inst(14L, 4L, "2026-11-28", 45_000L),
      inst(15L, 4L, "2026-12-05", 45_000L) // Next month: must NOT be deducted!
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-01", 800_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = debts,
      installments = installments
    )

    assertEquals(800_000L, summary.actualReceivedIncomeCents)
    assertEquals(200_000L, summary.periodDebtTotalCents)
    assertEquals(4, summary.periodDebtItems.size)
    assertEquals(600_000L, summary.availableAfterDebtCents)
    assertEquals(600_000L, summary.projectedRemainingCents)
  }

  @Test
  fun regression_weeklyBudget_deductsTwoLoansDueThisWeekAndExcludesNextWeekLoans() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Weekly Baon",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 200_000L, // ₱2,000
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val debts = listOf(
      debt(1L, "Loan A", 60_000L),
      debt(2L, "Loan B", 50_000L),
      debt(3L, "Loan C Next Week", 40_000L),
      debt(4L, "Loan D Next Week", 30_000L)
    )
    val installments = listOf(
      inst(11L, 1L, "2026-11-12", 30_000L),
      inst(12L, 2L, "2026-11-16", 25_000L),
      inst(13L, 3L, "2026-11-17", 40_000L), // EndDateExclusive boundary = next week!
      inst(14L, 4L, "2026-11-20", 30_000L)
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-10", 200_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = debts,
      installments = installments
    )

    assertEquals(200_000L, summary.expectedIncomeCents)
    assertEquals(55_000L, summary.periodDebtTotalCents)
    assertEquals(145_000L, summary.availableAfterDebtCents)
  }

  @Test
  fun regression_every14DaysBudget_deductsInsideWindowAndIgnoresOutsideWindow() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Biweekly Stipend",
      frequency = IncomeFrequency.EVERY_14_DAYS,
      expectedAmountCents = 300_000L, // ₱3,000
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val debts = listOf(debt(1L, "Cashalo", 200_000L))
    val installments = listOf(
      inst(11L, 1L, "2026-11-18", 100_000L),
      inst(12L, 1L, "2026-11-24", 90_000L) // Day 14 = next period start
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-10", 300_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = debts,
      installments = installments
    )

    assertEquals(100_000L, summary.periodDebtTotalCents)
    assertEquals(200_000L, summary.availableAfterDebtCents)
  }

  @Test
  fun regression_every21DaysBudget_deductsExact21DayObligations() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Every 3 Weeks",
      frequency = IncomeFrequency.EVERY_21_DAYS,
      expectedAmountCents = 450_000L, // ₱4,500
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val debts = listOf(debt(1L, "Home Credit", 270_000L))
    val installments = listOf(
      inst(11L, 1L, "2026-11-25", 135_000L),
      inst(12L, 1L, "2026-12-01", 135_000L) // Next 21-day cycle
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-10", 450_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = debts,
      installments = installments
    )

    assertEquals(135_000L, summary.periodDebtTotalCents)
    assertEquals(315_000L, summary.availableAfterDebtCents)
  }

  @Test
  fun regression_dailyBudget_deductsExactCentavoPaymentDueToday() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Daily Allowance",
      frequency = IncomeFrequency.DAILY,
      expectedAmountCents = 15_000L, // ₱150.00
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val debts = listOf(debt(1L, "GCash Cash Loan", 10_356L))
    val installments = listOf(
      inst(11L, 1L, "2026-11-10", 5_178L), // ₱51.78 due today
      inst(12L, 1L, "2026-11-17", 5_178L)
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-10", 15_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = debts,
      installments = installments
    )

    assertEquals(5_178L, summary.periodDebtTotalCents)
    assertEquals(9_822L, summary.availableAfterDebtCents) // ₱98.22
  }

  @Test
  fun regression_partialPayment_countsActualPaidAsOutflowAndOnlyRemainingAsUnpaidDebt() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Weekly Baon",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 200_000L,
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)
    val debts = listOf(debt(1L, "Maya Loan", 20_000L, 50_000L))
    val installments = listOf(
      inst(
        id = 11L,
        debtId = 1L,
        dueDateIso = "2026-11-14",
        amountDueCents = 50_000L,
        paidAmountCents = 30_000L,
        status = InstallmentStatus.PARTIAL
      )
    )
    val txs = listOf(
      TransactionEntity(
        id = 100L,
        type = TransactionType.DEBT_REPAYMENT,
        title = "Maya Loan partial",
        amountCents = 30_000L,
        date = "2026-11-11",
        debtId = 1L,
        installmentId = 11L
      )
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-10", 200_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = txs,
      debts = debts,
      installments = installments
    )

    assertEquals(30_000L, summary.directCashRepaymentsCents)
    assertEquals(20_000L, summary.periodDebtTotalCents)
    assertEquals(170_000L, summary.actualRemainingCents)
    assertEquals(150_000L, summary.availableAfterDebtCents) // Total impact = 30k + 20k = 50k (NOT 80k!)
  }

  @Test
  fun regression_rollover_carriesActualUnusedMoneyForwardWithoutCreatingFakeIncome() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Weekly Baon",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 200_000L,
      startDateIso = "2026-11-02",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(
      isConfigured = true,
      rolloverEnabled = true,
      primarySourceId = 1L
    )
    val debts = listOf(debt(1L, "GLoan", 30_000L, 50_000L))
    val installments = listOf(
      inst(11L, 1L, "2026-11-05", 50_000L, paidAmountCents = 50_000L, status = InstallmentStatus.PAID),
      inst(12L, 1L, "2026-11-12", 30_000L, paidAmountCents = 0L, status = InstallmentStatus.UPCOMING)
    )
    val occurrences = listOf(
      receivedOccurrence(1L, 1L, "2026-11-02", 200_000L),
      receivedOccurrence(2L, 1L, "2026-11-09", 200_000L)
    )
    val expenses = listOf(
      ExpenseEntity(id = 1L, date = "2026-11-06", category = ExpenseCategory.SCHOOL_FOOD, title = "Food", amountCents = 80_000L)
    )
    val txs = listOf(
      TransactionEntity(
        id = 1L,
        type = TransactionType.DEBT_REPAYMENT,
        title = "GLoan #1",
        amountCents = 50_000L,
        date = "2026-11-05",
        debtId = 1L,
        installmentId = 11L
      )
    )

    // Period 1 (Nov 2–8): ₱0 + ₱2,000 - ₱500 debt paid - ₱800 expense = ₱700 ending
    val p1 = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = occurrences,
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = expenses,
      reserves = emptyList(),
      transactions = txs,
      debts = debts,
      installments = installments,
      periodOffset = -1
    )
    assertEquals(0L, p1.openingCarryoverCents)
    assertEquals(70_000L, p1.actualRemainingCents)
    assertEquals(70_000L, p1.availableAfterDebtCents)

    // Period 2 (Nov 9–15): ₱700 carryover + ₱2,000 received - ₱300 unpaid debt = ₱2,400 available
    val p2 = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = occurrences,
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = expenses,
      reserves = emptyList(),
      transactions = txs,
      debts = debts,
      installments = installments,
      periodOffset = 0
    )
    assertEquals(70_000L, p2.openingCarryoverCents)
    assertEquals(200_000L, p2.expectedIncomeCents) // Carryover is NOT new income!
    assertEquals(30_000L, p2.periodDebtTotalCents)
    assertEquals(240_000L, p2.availableAfterDebtCents)

    // Unreceived Period 1 allowance must NOT roll over as actual cash; disabling rollover sets carryover = 0
    val unreceivedP2 = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(
        receivedOccurrence(1L, 1L, "2026-11-02", 0L, 200_000L, IncomeOccurrenceStatus.EXPECTED),
        receivedOccurrence(2L, 1L, "2026-11-09", 200_000L)
      ),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = emptyList(),
      installments = emptyList(),
      periodOffset = 0
    )
    assertEquals(0L, unreceivedP2.openingCarryoverCents)

    val noRolloverP2 = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile.copy(rolloverEnabled = false),
      sources = listOf(src),
      occurrences = occurrences,
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = expenses,
      reserves = emptyList(),
      transactions = txs,
      debts = debts,
      installments = installments,
      periodOffset = 0
    )
    assertEquals(0L, noRolloverP2.openingCarryoverCents)
  }

  // --- PART 25B, 25C, 25D REMAINING VERIFICATION SCENARIOS ---

  @Test
  fun debtIntegration_coversRevolvingMinDueBnplChildExclusionRestructuredVersionsClosedUnknownOverdue() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Monthly",
      frequency = IncomeFrequency.MONTHLY,
      expectedAmountCents = 1_000_000L,
      startDateIso = "2026-11-01",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(isConfigured = true, primarySourceId = 1L)

    // 1. Revolving credit: ₱50k credit limit must be IGNORED, ₱1,250 minDue used
    val creditLine = debt(
      id = 1L,
      name = "REVI Credit",
      balanceCents = 500_000L,
      instCents = 0L,
      classification = LoanProductClassification.REVOLVING_CREDIT,
      minimumDueCents = 125_000L,
      creditLimitCents = 5_000_000L,
      nextDueDate = "2026-11-20"
    )
    // 2. BNPL billing statement child excluded when linked to parent
    val bnplParent = debt(2L, "SPayLater Purchases", 200_000L, 100_000L, nextDueDate = "2026-11-15")
    val bnplStatement = debt(
      id = 3L,
      name = "SPayLater Nov Bill",
      balanceCents = 100_000L,
      obligationRole = ObligationRole.BILLING_STATEMENT,
      nextDueDate = "2026-11-15"
    ).copy(parentDebtId = 2L)
    // 3. Restructured loan scheduleVersion = 2 ignores v1 rows
    val restructured = debt(4L, "Skyro Restructured", 120_000L, 40_000L, scheduleVersion = 2)
    // 4. Closed / PAID loan excluded
    val paidDebt = debt(5L, "Old Closed", 0L, 50_000L, status = DebtStatus.PAID, nextDueDate = "2026-11-12")
    // 5. Overdue + Unknown amount installment + Variable installments
    val overdueAndVarDebt = debt(6L, "Home Credit Var", 90_000L, 30_000L)
    val unknownAmtDebt = debt(7L, "Unknown Loan", 50_000L, 0L, nextDueDate = "2026-11-22")

    val installments = listOf(
      inst(101L, 4L, "2026-11-11", 90_000L, scheduleVersion = 1), // Obsolete v1 -> ignored
      inst(102L, 4L, "2026-11-18", 40_000L, scheduleVersion = 2), // Active v2 -> deducted
      inst(103L, 6L, "2026-10-28", 25_000L, status = InstallmentStatus.OVERDUE), // Overdue from Oct!
      inst(104L, 6L, "2026-11-25", 35_000L, status = InstallmentStatus.UPCOMING), // Variable amount 350 != 300
      inst(105L, 7L, "2026-11-22", 0L, status = InstallmentStatus.UPCOMING) // Unknown amount -> flagged, not invented
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(1L, 1L, "2026-11-01", 1_000_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = listOf(creditLine, bnplParent, bnplStatement, restructured, paidDebt, overdueAndVarDebt, unknownAmtDebt),
      installments = installments
    )

    // Period Nov debt: REVI 125k + bnplParent 100k + Skyro v2 40k + HomeCredit Nov 35k = 300k
    assertEquals(300_000L, summary.periodDebtTotalCents)
    // Prior overdue separated cleanly: HomeCredit Oct 25k
    assertEquals(25_000L, summary.overdueDebtTotalCents)
    assertTrue(summary.hasOverdueObligations)
    assertTrue(summary.hasUnknownAmountObligations)
    assertEquals(700_000L, summary.projectedRemainingCents + 25_000L) // before overdue = 700k, after overdue = 675k
    assertEquals(675_000L, summary.projectedRemainingCents)
  }

  @Test
  fun allocationsAndReservesAndDeficitAndLowerActualIncome_preventDoubleCountingAndReflectRealCash() {
    val src = IncomeSourceEntity(
      id = 1L,
      name = "Weekly",
      frequency = IncomeFrequency.WEEKLY,
      expectedAmountCents = 200_000L,
      startDateIso = "2026-11-10",
      isPrimary = true
    )
    val profile = BudgetProfileEntity(
      isConfigured = true,
      reserveAheadEnabled = true,
      primarySourceId = 1L
    )
    // Future payment ₱1,200 due in 3 weekly cycles (Dec 1)
    val futureLoan = debt(1L, "Future GLoan", 120_000L, 120_000L)
    val futureInst = inst(1L, 1L, "2026-12-01", 120_000L)

    // Food allocation ₱1,500, recorded food expense ₱600 -> ₱900 remaining allocation
    val allocs = listOf(
      BudgetAllocationEntity(id = 1L, categoryKey = BudgetCategoryKey.FOOD, allocatedAmountCents = 150_000L, isEnabled = true)
    )
    val expenses = listOf(
      ExpenseEntity(id = 1L, date = "2026-11-11", category = ExpenseCategory.SCHOOL_FOOD, title = "Meals", amountCents = 60_000L)
    )
    // Active reserve of ₱400 created on Nov 10 for Future GLoan covers recommended ₱400 this cycle -> no double deduction!
    val reserves = listOf(
      DebtReserveEntity(
        id = 1L,
        weekStartDate = "2026-11-10",
        targetDebtId = 1L,
        reserveType = ReserveType.DEBT_REPAYMENT_RESERVE,
        amountCents = 40_000L,
        status = ReserveStatus.RESERVED
      )
    )
    val txs = listOf(
      TransactionEntity(
        id = 1L,
        type = TransactionType.DEBT_RESERVE,
        title = "Debt Repayment Reserve",
        amountCents = 40_000L,
        date = "2026-11-10",
        debtId = 1L,
        reserveId = 1L
      )
    )
    // Actual income received lower than expected: ₱1,500 received instead of ₱2,000
    val lowerOcc = receivedOccurrence(
      id = 1L,
      sourceId = 1L,
      dateIso = "2026-11-10",
      amountCents = 150_000L,
      expectedAmountCents = 200_000L,
      status = IncomeOccurrenceStatus.PARTIALLY_RECEIVED
    )

    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = listOf(src),
      occurrences = listOf(lowerOcc),
      allocations = allocs,
      allowances = emptyList(),
      expenses = expenses,
      reserves = reserves,
      transactions = txs,
      debts = listOf(futureLoan),
      installments = listOf(futureInst)
    )

    assertEquals(150_000L, summary.actualReceivedIncomeCents)
    assertEquals(50_000L, summary.unreceivedConfirmedDeltaCents) // ₱500 shortfall tracked honestly
    assertEquals(60_000L, summary.actualExpensesCents)
    assertEquals(40_000L, summary.netDebtReservedInPeriodCents)
    // Actual remaining = 150k received - 60k spent - 40k reserved = 50k
    assertEquals(50_000L, summary.actualRemainingCents)
    // Category Food: 150k - 60k = 90k remaining
    val foodCat = summary.categoryAllocations.single()
    assertEquals(90_000L, foodCat.remainingAllocationCents)
    assertFalse(foodCat.isOverspent)
    // Reserve-ahead plan needed 40k/cycle, already reserved 40k this week -> 0 extra planned reserve needed!
    assertEquals(0L, summary.plannedFutureReservesThisCycleCents)
    // Projected deficit: 50k cash - 90k remaining food plan = -40k
    assertEquals(-40_000L, summary.projectedRemainingCents)
    assertTrue(summary.isProjectedDeficit)

    // When reserve is applied to pay future installment, debt covered by reserve prevents double subtraction!
    val decPeriod = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 12, 1),
      profile = profile.copy(rolloverEnabled = false),
      sources = listOf(src),
      occurrences = listOf(receivedOccurrence(2L, 1L, "2026-12-01", 200_000L)),
      allocations = emptyList(),
      allowances = emptyList(),
      expenses = emptyList(),
      reserves = reserves, // 40k active reserve still exists
      transactions = emptyList(),
      debts = listOf(futureLoan),
      installments = listOf(futureInst)
    )
    assertEquals(120_000L, decPeriod.periodDebtTotalCents)
    assertEquals(40_000L, decPeriod.debtCoveredByExistingReserveCents)
    assertEquals(120_000L, decPeriod.availableAfterDebtCents) // 200k - (120k - 40k reserved) = 120k!
  }

  @Test
  fun legacyStandaloneAllowances_beforeOrWithoutBudgetOccurrencesStillCountAccurately() {
    val profile = BudgetProfileEntity(isConfigured = false, rolloverEnabled = false)
    val summary = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = profile,
      sources = emptyList(),
      occurrences = emptyList(),
      allocations = emptyList(),
      allowances = listOf(
        AllowanceRecordEntity(
          id = 1L,
          weekStartDate = "2026-11-09",
          receivedDate = "2026-11-10",
          amountCents = 150_000L,
          sourceLabel = "Manual Baon"
        )
      ),
      expenses = emptyList(),
      reserves = emptyList(),
      transactions = emptyList(),
      debts = emptyList(),
      installments = emptyList()
    )

    assertEquals(150_000L, summary.actualReceivedIncomeCents)
    assertEquals(150_000L, summary.actualRemainingCents)
    assertEquals(0L, summary.periodDebtTotalCents)
  }
}
