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
import com.baonplan.finance.domain.model.DebtDeductionMode
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

object CashFlowBudgetEngine {

  fun calculateOverview(
    today: LocalDate,
    profile: BudgetProfileEntity?,
    sources: List<IncomeSourceEntity>,
    occurrences: List<IncomeOccurrenceEntity>,
    allocations: List<BudgetAllocationEntity>,
    allowances: List<AllowanceRecordEntity>,
    expenses: List<ExpenseEntity>,
    reserves: List<DebtReserveEntity>,
    transactions: List<TransactionEntity>,
    debts: List<DebtEntity>,
    installments: List<InstallmentEntity>,
    periodOffset: Int = 0
  ): BudgetOverviewSummary {
    val activeProfile = profile ?: BudgetProfileEntity()
    val hasConfiguredSources = sources.isNotEmpty()
    val isConfigured = activeProfile.isConfigured || hasConfiguredSources
    val primarySource = BudgetRecurrenceEngine.resolvePrimarySource(activeProfile, sources)
    val window = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = today,
      profile = activeProfile,
      sources = sources,
      periodOffset = periodOffset
    )

    val allObligations = ObligationExtractor.extractPayableObligations(
      debts = debts,
      installments = installments,
      today = today
    )

    // Rollover chain: compute actual cash strictly before window.startDate when rollover is enabled.
    val openingCarryoverCents = if (activeProfile.rolloverEnabled) {
      computeActualCashBalanceBeforeDate(
        beforeExclusive = window.startDate,
        occurrences = occurrences,
        allowances = allowances,
        expenses = expenses,
        transactions = transactions
      )
    } else {
      0L
    }

    // Resolve income occurrences for the inspected period.
    val resolvedOccurrences = BudgetRecurrenceEngine.resolveOccurrencesForWindow(
      sources = sources,
      persistedOccurrences = occurrences,
      window = window,
      primarySourceId = primarySource?.id
    )

    val standaloneAllowancesInWindow = allowances.filter { rec ->
      rec.incomeOccurrenceId == null &&
        ManilaTime.parseDate(rec.receivedDate)?.let { it in window } == true
    }

    val expectedInPeriodCents = MoneyFormatter.sumCentsExact(
      resolvedOccurrences.mapNotNull { occ ->
        if (occ.expectedDate in window && occ.status != IncomeOccurrenceStatus.CANCELLED) {
          occ.expectedAmountCents.coerceAtLeast(0L)
        } else null
      }
    ) + MoneyFormatter.sumCentsExact(standaloneAllowancesInWindow.map { it.amountCents.coerceAtLeast(0L) })

    val pendingExpectedInPeriodCents = MoneyFormatter.sumCentsExact(
      resolvedOccurrences.mapNotNull { occ ->
        if (occ.expectedDate in window && occ.status == IncomeOccurrenceStatus.EXPECTED) {
          occ.expectedAmountCents.coerceAtLeast(0L)
        } else null
      }
    )

    val actualReceivedFromOccurrencesCents = MoneyFormatter.sumCentsExact(
      resolvedOccurrences.mapNotNull { occ ->
        if (occ.status.isSettledCashIn) {
          val settledDate = occ.actualReceivedDate ?: occ.expectedDate
          if (settledDate in window) occ.actualReceivedAmountCents.coerceAtLeast(0L) else null
        } else null
      }
    )

    val actualReceivedIncomeCents = actualReceivedFromOccurrencesCents +
      MoneyFormatter.sumCentsExact(standaloneAllowancesInWindow.map { it.amountCents.coerceAtLeast(0L) })

    val settledShortfallDelta = MoneyFormatter.sumCentsExact(
      resolvedOccurrences.mapNotNull { occ ->
        if (occ.expectedDate in window &&
          (occ.status == IncomeOccurrenceStatus.PARTIALLY_RECEIVED ||
            occ.status == IncomeOccurrenceStatus.MISSED)
        ) {
          (occ.expectedAmountCents - occ.actualReceivedAmountCents).coerceAtLeast(0L)
        } else null
      }
    )

    // Partition debt obligations strictly cleanly (< startDate overdue vs [startDate, endExclusive) due).
    val periodDebtItems = allObligations.filter { item ->
      item.dueDate != null && item.dueDate in window
    }
    val priorOverdueItems = allObligations.filter { item ->
      item.dueDate != null &&
        item.dueDate.isBefore(window.startDate) &&
        item.isOverdue
    }
    val unconfirmedDateObligations = allObligations.filter { it.dueDate == null }

    fun deductionAmount(item: BudgetDebtObligationItem): Long {
      if (!item.isAmountKnown) return 0L
      return when (activeProfile.debtDeductionMode) {
        DebtDeductionMode.UNPAID_ONLY -> item.unpaidAmountCents
        DebtDeductionMode.FULL_DUE -> item.amountDueCents
      }.coerceAtLeast(0L)
    }

    val periodDebtTotalCents = MoneyFormatter.sumCentsExact(periodDebtItems.map { deductionAmount(it) })
    val overdueDebtTotalCents = MoneyFormatter.sumCentsExact(priorOverdueItems.map { deductionAmount(it) })

    // Cash outflows inside [window.startDate, window.endDateExclusive)
    val periodExpenses = expenses.filter { exp ->
      ManilaTime.parseDate(exp.date)?.let { it in window } == true
    }
    val periodTransactions = transactions.filter { tx ->
      !tx.isVoided && ManilaTime.parseDate(tx.date)?.let { it in window } == true
    }
    val reservesById = reserves.associateBy { it.id }

    val totalActualExpensesCents = MoneyFormatter.sumCentsExact(periodExpenses.map { it.amountCents })
    val emergencyFundedExpensesCents = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { it.type == TransactionType.EXPENSE && it.fundedFromReserveCents > 0L }
        .map { minOf(it.amountCents, it.fundedFromReserveCents) }
    )
    val cashExpensesCents = (totalActualExpensesCents - emergencyFundedExpensesCents).coerceAtLeast(0L)

    val directCashRepaymentsCents = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { it.type == TransactionType.DEBT_REPAYMENT }
        .map { (it.amountCents - it.fundedFromReserveCents).coerceAtLeast(0L) }
    )

    val debtReserveDepositsInPeriod = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { it.type == TransactionType.DEBT_RESERVE }
        .map { it.amountCents.coerceAtLeast(0L) }
    )
    val emergencyDepositsInPeriod = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { it.type == TransactionType.EMERGENCY_SAVINGS }
        .map { it.amountCents.coerceAtLeast(0L) }
    )

    val emergencyWithdrawalsInPeriod = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { it.type == TransactionType.ADJUSTMENT && it.category == "Emergency Withdrawal" }
        .map { it.amountCents.coerceAtLeast(0L) }
    )
    val debtReserveReleasesInPeriod = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { tx ->
          tx.type == TransactionType.ADJUSTMENT &&
            tx.category == "Reserve Release" &&
            tx.reserveId?.let { reservesById[it]?.reserveType } == ReserveType.DEBT_REPAYMENT_RESERVE
        }
        .map { it.amountCents.coerceAtLeast(0L) }
    )
    val emergencyReserveReleasesInPeriod = MoneyFormatter.sumCentsExact(
      periodTransactions
        .filter { tx ->
          tx.type == TransactionType.ADJUSTMENT &&
            tx.category == "Reserve Release" &&
            tx.reserveId?.let { reservesById[it]?.reserveType } == ReserveType.EMERGENCY_SAVINGS
        }
        .map { it.amountCents.coerceAtLeast(0L) }
    )

    val netDebtReservedInPeriod = debtReserveDepositsInPeriod - debtReserveReleasesInPeriod
    val netEmergencySavedInPeriod = emergencyDepositsInPeriod - emergencyWithdrawalsInPeriod - emergencyReserveReleasesInPeriod
    val netReserveCashLockInPeriod = netDebtReservedInPeriod + netEmergencySavedInPeriod

    // Active reserve balance available across active DEBT_REPAYMENT_RESERVE buckets
    val totalActiveDebtReserveCents = MoneyFormatter.sumCentsExact(
      reserves
        .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE && it.status == ReserveStatus.RESERVED }
        .map { it.remainingReservedCents }
    )

    // Optional Category Allocations (never fabricated if unconfigured!)
    val enabledAllocations = allocations.filter { it.isEnabled && it.allocatedAmountCents > 0L }
    val categorySummaries = enabledAllocations.map { alloc ->
      val spent = if (alloc.categoryKey.isSavingsAllocation) {
        netEmergencySavedInPeriod.coerceAtLeast(0L)
      } else {
        spentForCategoryKey(alloc.categoryKey, periodExpenses)
      }
      val remaining = alloc.allocatedAmountCents - spent
      BudgetCategorySummary(
        categoryKey = alloc.categoryKey,
        label = alloc.categoryKey.label,
        allocatedCents = alloc.allocatedAmountCents,
        actualSpentCents = spent,
        remainingAllocationCents = remaining,
        isOverspent = spent > alloc.allocatedAmountCents
      )
    }
    val totalPlannedCategoryRemainingCents = MoneyFormatter.sumCentsExact(
      categorySummaries.map { it.remainingAllocationCents.coerceAtLeast(0L) }
    )

    // Optional Upcoming Payment Reserves (Part 12: reserve ahead over cycles until due date)
    val upcomingPlans = if (activeProfile.reserveAheadEnabled) {
      computeUpcomingReservePlans(
        today = today,
        currentWindow = window,
        profile = activeProfile,
        sources = sources,
        reserves = reserves,
        allObligations = allObligations
      )
    } else {
      emptyList()
    }
    val plannedFutureReservesThisCycleCents = MoneyFormatter.sumCentsExact(
      upcomingPlans.map { plan ->
        val reservedForDebtThisPeriod = MoneyFormatter.sumCentsExact(
          reserves.filter {
            it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE &&
              it.status == ReserveStatus.RESERVED &&
              it.targetDebtId == plan.debtId &&
              it.weekStartDate == ManilaTime.formatIso(window.startDate)
          }.map { it.remainingReservedCents }
        )
        (plan.recommendedThisCycleCents - reservedForDebtThisPeriod).coerceAtLeast(0L)
      }
    )

    // Prevent double-counting debt obligations already backed by active debt reserves!
    val debtCoveredByExistingReserveCents = minOf(periodDebtTotalCents, totalActiveDebtReserveCents)
    val unfundedPeriodDebtCents = (periodDebtTotalCents - debtCoveredByExistingReserveCents).coerceAtLeast(0L)
    val remainingActiveReserveForOverdue = (totalActiveDebtReserveCents - debtCoveredByExistingReserveCents).coerceAtLeast(0L)
    val unfundedOverdueDebtCents = (overdueDebtTotalCents - remainingActiveReserveForOverdue).coerceAtLeast(0L)

    // Actual Cash on Hand right now:
    // Opening Actual Cash + Actual Received Cash - Actual Cash Expenses - Direct Cash Repayments - Net Cash Moved Into Reserves
    val actualCashOnHandCents = openingCarryoverCents +
      actualReceivedIncomeCents -
      cashExpensesCents -
      directCashRepaymentsCents -
      netReserveCashLockInPeriod

    val pendingExpectedContribution = if (activeProfile.includeExpectedIncomeInProjection) {
      pendingExpectedInPeriodCents
    } else {
      0L
    }

    val projectedBeforeOverdueCents = actualCashOnHandCents +
      pendingExpectedContribution -
      unfundedPeriodDebtCents -
      totalPlannedCategoryRemainingCents -
      plannedFutureReservesThisCycleCents

    val projectedAfterOverdueCents = projectedBeforeOverdueCents - unfundedOverdueDebtCents
    val primaryProjectedRemainingCents = if (activeProfile.includeOverdueInBudget) {
      projectedAfterOverdueCents
    } else {
      projectedBeforeOverdueCents
    }

    val overdueAppliedToActualAndAvailable = if (activeProfile.includeOverdueInBudget) {
      unfundedOverdueDebtCents
    } else {
      0L
    }
    val actualAfterUnpaidDebtCents = actualCashOnHandCents - unfundedPeriodDebtCents - overdueAppliedToActualAndAvailable
    val availableAfterDebtCents = actualCashOnHandCents +
      pendingExpectedContribution -
      unfundedPeriodDebtCents -
      overdueAppliedToActualAndAvailable

    val nextExpected = BudgetRecurrenceEngine.findNextExpectedIncome(
      sources = sources,
      persistedOccurrences = occurrences,
      fromDate = today
    )

    val allRelevantDebtItems = periodDebtItems + priorOverdueItems

    return BudgetOverviewSummary(
      isConfigured = isConfigured,
      periodWindow = window,
      periodOffset = periodOffset,
      openingCarryoverCents = openingCarryoverCents,
      rolloverEnabled = activeProfile.rolloverEnabled,
      expectedIncomeCents = expectedInPeriodCents,
      pendingExpectedIncomeCents = pendingExpectedInPeriodCents,
      actualReceivedIncomeCents = actualReceivedIncomeCents,
      unreceivedConfirmedDeltaCents = settledShortfallDelta,
      periodDebtTotalCents = periodDebtTotalCents,
      periodDebtItems = periodDebtItems,
      overdueDebtTotalCents = overdueDebtTotalCents,
      overdueDebtItems = priorOverdueItems,
      debtCoveredByExistingReserveCents = debtCoveredByExistingReserveCents,
      actualExpensesCents = totalActualExpensesCents,
      emergencyFundedExpensesCents = emergencyFundedExpensesCents,
      directCashRepaymentsCents = directCashRepaymentsCents,
      netDebtReservedInPeriodCents = netDebtReservedInPeriod,
      netEmergencySavedInPeriodCents = netEmergencySavedInPeriod,
      totalActiveDebtReserveCents = totalActiveDebtReserveCents,
      categoryAllocations = categorySummaries,
      totalPlannedCategoryRemainingCents = totalPlannedCategoryRemainingCents,
      plannedFutureReservesThisCycleCents = plannedFutureReservesThisCycleCents,
      upcomingReservePlans = upcomingPlans,
      projectedRemainingCents = primaryProjectedRemainingCents,
      projectedRemainingAfterOverdueCents = projectedAfterOverdueCents,
      actualRemainingCents = actualCashOnHandCents,
      actualAfterUnpaidDebtCents = actualAfterUnpaidDebtCents,
      availableAfterDebtCents = availableAfterDebtCents,
      isProjectedDeficit = primaryProjectedRemainingCents < 0L,
      isActualDeficit = actualCashOnHandCents < 0L,
      hasUnknownAmountObligations = allRelevantDebtItems.any { !it.isAmountKnown },
      hasEstimatedObligations = allRelevantDebtItems.any { it.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION },
      hasOverdueObligations = priorOverdueItems.isNotEmpty() || periodDebtItems.any { it.isOverdue },
      hasPendingExpectedReceipts = resolvedOccurrences.any {
        it.status == IncomeOccurrenceStatus.EXPECTED && !it.expectedDate.isAfter(today)
      },
      nextExpectedIncomeDate = nextExpected?.expectedDate,
      nextExpectedIncomeAmountCents = nextExpected?.expectedAmountCents,
      nextExpectedIncomeSourceName = nextExpected?.sourceName,
      occurrencesInPeriod = resolvedOccurrences,
      activeSourcesCount = sources.count { it.isActive && !it.isPaused },
      pausedSourcesCount = sources.count { it.isActive && it.isPaused },
      hasUnconfirmedDateObligations = unconfirmedDateObligations.isNotEmpty(),
      unconfirmedDateObligationsCount = unconfirmedDateObligations.size
    )
  }

  /**
   * Computes exact historical cash balance strictly prior to [beforeExclusive].
   * Strictly excludes unreceived expected income and unpaid future debt obligations so only
   * confirmed cash inflows and confirmed cash outflows ever roll forward.
   */
  fun computeActualCashBalanceBeforeDate(
    beforeExclusive: LocalDate,
    occurrences: List<IncomeOccurrenceEntity>,
    allowances: List<AllowanceRecordEntity>,
    expenses: List<ExpenseEntity>,
    transactions: List<TransactionEntity>
  ): Long {
    val receivedFromOccurrences = MoneyFormatter.sumCentsExact(
      occurrences.mapNotNull { occ ->
        if (!occ.status.isSettledCashIn) return@mapNotNull null
        val settledDate = ManilaTime.parseDate(occ.actualReceivedDateIso ?: occ.expectedDateIso)
          ?: return@mapNotNull null
        if (settledDate.isBefore(beforeExclusive)) occ.actualReceivedAmountCents.coerceAtLeast(0L) else null
      }
    )

    val standaloneAllowances = MoneyFormatter.sumCentsExact(
      allowances.mapNotNull { rec ->
        if (rec.incomeOccurrenceId != null) return@mapNotNull null
        val recDate = ManilaTime.parseDate(rec.receivedDate) ?: return@mapNotNull null
        if (recDate.isBefore(beforeExclusive)) rec.amountCents.coerceAtLeast(0L) else null
      }
    )

    val priorTxs = transactions.filter { tx ->
      !tx.isVoided && ManilaTime.parseDate(tx.date)?.isBefore(beforeExclusive) == true
    }

    val totalExpenses = MoneyFormatter.sumCentsExact(
      expenses.mapNotNull { exp ->
        val d = ManilaTime.parseDate(exp.date) ?: return@mapNotNull null
        if (d.isBefore(beforeExclusive)) exp.amountCents.coerceAtLeast(0L) else null
      }
    )

    val emergencyFundedExpenses = MoneyFormatter.sumCentsExact(
      priorTxs.filter { it.type == TransactionType.EXPENSE && it.fundedFromReserveCents > 0L }
        .map { minOf(it.amountCents, it.fundedFromReserveCents) }
    )

    val directCashRepayments = MoneyFormatter.sumCentsExact(
      priorTxs.filter { it.type == TransactionType.DEBT_REPAYMENT }
        .map { (it.amountCents - it.fundedFromReserveCents).coerceAtLeast(0L) }
    )

    val reserveDeposits = MoneyFormatter.sumCentsExact(
      priorTxs.filter {
        it.type == TransactionType.DEBT_RESERVE || it.type == TransactionType.EMERGENCY_SAVINGS
      }.map { it.amountCents.coerceAtLeast(0L) }
    )

    val reserveReturns = MoneyFormatter.sumCentsExact(
      priorTxs.filter {
        it.type == TransactionType.ADJUSTMENT &&
          (it.category == "Emergency Withdrawal" || it.category == "Reserve Release")
      }.map { it.amountCents.coerceAtLeast(0L) }
    )

    val cashOutExpenses = (totalExpenses - emergencyFundedExpenses).coerceAtLeast(0L)
    return receivedFromOccurrences +
      standaloneAllowances -
      cashOutExpenses -
      directCashRepayments -
      reserveDeposits +
      reserveReturns
  }

  private fun spentForCategoryKey(
    key: BudgetCategoryKey,
    periodExpenses: List<ExpenseEntity>
  ): Long {
    val mapped = key.mappedExpenseCategory ?: return 0L
    val directMatches = periodExpenses.filter { it.category == mapped }
    val extras = if (key == BudgetCategoryKey.OTHER) {
      periodExpenses.filter { it.category == ExpenseCategory.EMERGENCY }
    } else emptyList()
    return MoneyFormatter.sumCentsExact((directMatches + extras).map { it.amountCents.coerceAtLeast(0L) })
  }

  private fun computeUpcomingReservePlans(
    today: LocalDate,
    currentWindow: BudgetPeriodWindow,
    profile: BudgetProfileEntity,
    sources: List<IncomeSourceEntity>,
    reserves: List<DebtReserveEntity>,
    allObligations: List<BudgetDebtObligationItem>
  ): List<UpcomingReservePlan> {
    val horizonEnd = currentWindow.startDate.plusDays(90)
    val futureObligations = allObligations.filter { item ->
      item.isAmountKnown &&
        item.unpaidAmountCents > 0L &&
        item.dueDate != null &&
        !item.dueDate.isBefore(currentWindow.endDateExclusive) &&
        !item.dueDate.isAfter(horizonEnd)
    }
    if (futureObligations.isEmpty()) return emptyList()

    val reservesByDebt = reserves
      .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE && it.status == ReserveStatus.RESERVED }
      .groupBy { it.targetDebtId }

    // Prebuild future windows starting at currentWindow
    val forwardWindows = (0..25).map { delta ->
      BudgetRecurrenceEngine.resolvePeriodWindow(
        today = today,
        profile = profile,
        sources = sources,
        periodOffset = currentWindow.periodOffset + delta
      )
    }

    val plans = mutableListOf<UpcomingReservePlan>()
    val remainingPoolByDebt = HashMap<Long, Long>()
    for ((debtId, debtReserves) in reservesByDebt) {
      if (debtId != null) {
        remainingPoolByDebt[debtId] = MoneyFormatter.sumCentsExact(debtReserves.map { it.remainingReservedCents })
      }
    }

    for (item in futureObligations.sortedBy { it.dueDate }) {
      val dueDate = item.dueDate ?: continue
      val existingForDebt = remainingPoolByDebt[item.debtId] ?: 0L
      val coveredHere = minOf(existingForDebt, item.unpaidAmountCents)
      remainingPoolByDebt[item.debtId] = (existingForDebt - coveredHere).coerceAtLeast(0L)
      val remainingNeeded = (item.unpaidAmountCents - coveredHere).coerceAtLeast(0L)
      if (remainingNeeded <= 0L) continue

      val cyclesBeforeDue = forwardWindows.count { w -> w.startDate.isBefore(dueDate) }.coerceAtLeast(1)
      val recommendedThisCycle = BigDecimal.valueOf(remainingNeeded)
        .divide(BigDecimal.valueOf(cyclesBeforeDue.toLong()), 0, RoundingMode.HALF_UP)
        .longValueExact()

      if (recommendedThisCycle > 0L) {
        plans.add(
          UpcomingReservePlan(
            debtId = item.debtId,
            debtName = item.debtName,
            dueDate = dueDate,
            dueDateIso = ManilaTime.formatIso(dueDate),
            totalDueCents = item.unpaidAmountCents,
            existingReservedForDebtCents = coveredHere,
            remainingToReserveCents = remainingNeeded,
            remainingCyclesBeforeDue = cyclesBeforeDue,
            recommendedThisCycleCents = recommendedThisCycle
          )
        )
      }
    }

    return plans
  }
}
