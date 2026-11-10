package com.baonplan.finance.domain

import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.DuesCoverageStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.GeneratedInstallmentSpec
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanCostBreakdown
import com.baonplan.finance.domain.model.NextPaymentItem
import com.baonplan.finance.domain.model.PayoffDebtCandidate
import com.baonplan.finance.domain.model.PayoffProjectionSummary
import com.baonplan.finance.domain.model.PaymentOverview
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.domain.model.PriorWeekCarryOverCandidate
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.RepaymentProgressSummary
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ScenarioPlan
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.model.WeeklyBudgetSummary
import com.baonplan.finance.domain.model.WeeklyDuesReadiness
import com.baonplan.finance.domain.model.WeeklyTrendPoint
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object FinancialCalculator {

  /**
   * Returns true when this debt record represents an independent liability rather than a
   * linked statement or converted installment plan whose underlying obligation is already tracked.
   */
  fun countsTowardTotalDebt(debt: DebtEntity): Boolean =
    debt.obligationRole.countsInTotalDebt || debt.parentDebtId == null

  fun calculateTotalActiveDebt(debts: List<DebtEntity>): Long =
    MoneyFormatter.sumCentsExact(
      debts.filter { it.status == DebtStatus.ACTIVE && countsTowardTotalDebt(it) }
        .map { it.remainingBalanceCents.coerceAtLeast(0L) }
    )

  fun calculateRepaymentProgress(debts: List<DebtEntity>): RepaymentProgressSummary {
    val primaryDebts = debts.filter { countsTowardTotalDebt(it) }
    val active = primaryDebts.filter { it.status == DebtStatus.ACTIVE }
    val completed = primaryDebts.filter { it.status == DebtStatus.PAID }
    val archived = primaryDebts.filter { it.status == DebtStatus.ARCHIVED }

    val totalActiveDebt = MoneyFormatter.sumCentsExact(active.map { it.remainingBalanceCents.coerceAtLeast(0L) })
    val completedOriginal = MoneyFormatter.sumCentsExact(
      completed.map {
        (it.totalScheduledRepaymentCents ?: it.originalPrincipalCents ?: it.priorPaidCents)
          .coerceAtLeast(0L)
      }
    )
    val trackedRepaidOnActive = MoneyFormatter.sumCentsExact(active.map { it.trackedPaidCents.coerceAtLeast(0L) })
    val knownPaidOnAll = MoneyFormatter.sumCentsExact(
      primaryDebts.map { (it.priorPaidCents + it.trackedPaidCents).coerceAtLeast(0L) }
    )

    val denominator = knownPaidOnAll + totalActiveDebt
    val fraction = if (denominator > 0L) {
      (knownPaidOnAll.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
    } else {
      0f
    }

    return RepaymentProgressSummary(
      totalActiveDebtCents = totalActiveDebt,
      completedLoansCount = completed.size,
      activeLoansCount = active.size,
      archivedLoansCount = archived.size,
      completedLoansOriginalTotalCents = completedOriginal,
      trackedRepaidOnActiveCents = trackedRepaidOnActive,
      totalKnownRepaidCents = knownPaidOnAll,
      progressFraction = fraction
    )
  }

  fun calculateLoanCostBreakdown(debt: DebtEntity): LoanCostBreakdown {
    val hasAnyFeeOrInterest =
      debt.interestCents != null || debt.serviceFeeCents != null || debt.processingFeeCents != null
    val totalFeesAndInterest = if (hasAnyFeeOrInterest) {
      (debt.interestCents ?: 0L) + (debt.serviceFeeCents ?: 0L) + (debt.processingFeeCents ?: 0L)
    } else {
      null
    }

    val computedTotalScheduled = debt.totalScheduledRepaymentCents
      ?: debt.originalPrincipalCents?.let { principal ->
        principal + (totalFeesAndInterest ?: 0L)
      }

    val totalKnownPaid = (debt.priorPaidCents + debt.trackedPaidCents).coerceAtLeast(0L)

    return LoanCostBreakdown(
      originalPrincipalCents = debt.originalPrincipalCents,
      amountReceivedCents = debt.amountReceivedCents,
      interestCents = debt.interestCents,
      serviceFeeCents = debt.serviceFeeCents,
      processingFeeCents = debt.processingFeeCents,
      totalFeesAndInterestCents = totalFeesAndInterest,
      totalScheduledRepaymentCents = computedTotalScheduled,
      priorPaidCents = debt.priorPaidCents,
      isPriorPaidKnown = debt.isPriorPaidKnown,
      trackedPaidCents = debt.trackedPaidCents,
      totalKnownPaidCents = totalKnownPaid,
      remainingBalanceCents = debt.remainingBalanceCents
    )
  }

  fun calculateWeeklyFoodBudget(
    schoolDaysPerWeek: Int,
    foodPerSchoolDayCents: Long
  ): Long = schoolDaysPerWeek.coerceAtLeast(0) * foodPerSchoolDayCents.coerceAtLeast(0L)

  /**
   * Calculates a safe weekly scenario plan.
   * Never assumes all remaining money after food is available for debt repayment:
   * explicitly sets aside other expenses, emergency savings, and a personal spending buffer.
   */
  fun calculateScenarioPlan(
    scenarioName: String,
    allowanceCents: Long,
    foodBudgetCents: Long,
    otherExpensesCents: Long,
    plannedDebtReserveCents: Long,
    emergencySavingsCents: Long
  ): ScenarioPlan {
    val safeAllowance = allowanceCents.coerceAtLeast(0L)
    val safeFood = foodBudgetCents.coerceAtLeast(0L)
    val safeOther = otherExpensesCents.coerceAtLeast(0L)
    val safeEmergency = emergencySavingsCents.coerceAtLeast(0L)
    val safeDebtReserve = plannedDebtReserveCents.coerceAtLeast(0L)

    val remainingAfterFood = safeAllowance - safeFood
    val remainingBeforeDebtAndBuffer = (remainingAfterFood - safeOther - safeEmergency).coerceAtLeast(0L)

    // Keep at least ₱50 (5,000 cents) or 20% of remaining cash as a personal safety buffer.
    val minimumPersonalBuffer = when {
      remainingBeforeDebtAndBuffer >= 10_000L -> 5_000L
      remainingBeforeDebtAndBuffer > 0L -> remainingBeforeDebtAndBuffer / 2L
      else -> 0L
    }

    val recommendedSafeDebtReserve =
      (remainingBeforeDebtAndBuffer - minimumPersonalBuffer).coerceAtLeast(0L)

    val remainingSpendingMoney =
      safeAllowance - safeFood - safeOther - safeDebtReserve - safeEmergency

    val capReason = if (remainingBeforeDebtAndBuffer <= minimumPersonalBuffer) {
      "Protect food, emergency savings, and basic personal buffer first."
    } else {
      "Preserves food, emergency savings, and at least ${com.baonplan.finance.domain.util.MoneyFormatter.formatPeso(minimumPersonalBuffer)} personal buffer."
    }

    return ScenarioPlan(
      scenarioName = scenarioName,
      allowanceCents = safeAllowance,
      foodBudgetCents = safeFood,
      remainingAfterFoodCents = remainingAfterFood,
      otherExpensesCents = safeOther,
      recommendedSafeDebtReserveCents = recommendedSafeDebtReserve,
      plannedDebtReserveCents = safeDebtReserve,
      emergencySavingsCents = safeEmergency,
      remainingSpendingMoneyCents = remainingSpendingMoney,
      isDeficit = remainingSpendingMoney < 0L,
      safeDebtCapReason = capReason
    )
  }

  fun calculateWeeklyBudgetSummary(
    today: LocalDate,
    settings: UserSettingsEntity,
    allowances: List<AllowanceRecordEntity>,
    expenses: List<ExpenseEntity>,
    reserves: List<DebtReserveEntity>,
    transactions: List<TransactionEntity>
  ): WeeklyBudgetSummary {
    val weekStart = ManilaTime.weekStart(today)
    val weekEnd = ManilaTime.weekEnd(today)
    val weekStartIso = ManilaTime.formatIso(weekStart)

    val recordedAllowanceThisWeek = allowances
      .filter { ManilaTime.isInWeek(it.receivedDate, weekStart) }
      .sumOf { it.amountCents }

    val weekExpenses = expenses.filter { ManilaTime.isInWeek(it.date, weekStart) }
    val actualFoodExpenses = weekExpenses
      .filter { it.category == ExpenseCategory.SCHOOL_FOOD }
      .sumOf { it.amountCents }
    val actualOtherExpenses = weekExpenses
      .filter { it.category != ExpenseCategory.SCHOOL_FOOD }
      .sumOf { it.amountCents }
    val schoolDaysLogged = weekExpenses
      .filter { it.isSchoolDay }
      .map { it.date }
      .distinct()
      .size

    // Active reserved debt money across all weeks (not yet paid as a debt repayment)
    val activeDebtReserveTotal = reserves
      .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE && it.status == ReserveStatus.RESERVED }
      .sumOf { it.remainingReservedCents }

    val appliedDebtReserveTotal = reserves
      .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE }
      .sumOf { it.appliedAmountCents }

    val reservesById = reserves.associateBy { it.id }

    // Cash set aside THIS week into reserves or emergency savings (excluding released reserves)
    val thisWeekDebtReserves = reserves.filter {
      it.weekStartDate == weekStartIso &&
        it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE &&
        it.status != ReserveStatus.RELEASED
    }
    val debtReservedThisWeek = thisWeekDebtReserves.sumOf { it.amountCents }
    val activeDebtReserveThisWeek = thisWeekDebtReserves
      .filter { it.status == ReserveStatus.RESERVED }
      .sumOf { it.remainingReservedCents }

    val emergencySavingsThisWeek = reserves
      .filter {
        it.weekStartDate == weekStartIso &&
          it.reserveType == ReserveType.EMERGENCY_SAVINGS &&
          it.status != ReserveStatus.RELEASED
      }
      .sumOf { it.amountCents }

    val weekTransactions = transactions.filter {
      !it.isVoided && ManilaTime.isInWeek(it.date, weekStart)
    }

    val weekRepayments = weekTransactions.filter {
      it.type == TransactionType.DEBT_REPAYMENT
    }

    val repaymentsPaidThisWeek = weekRepayments.sumOf { it.amountCents }
    val directRepaymentsFromCashThisWeek = weekRepayments.sumOf {
      (it.amountCents - it.fundedFromReserveCents).coerceAtLeast(0L)
    }

    val emergencyFundedExpensesThisWeek = weekTransactions
      .filter { it.type == TransactionType.EXPENSE && it.fundedFromReserveCents > 0L }
      .sumOf { minOf(it.amountCents, it.fundedFromReserveCents) }

    val emergencyWithdrawalsToCashThisWeek = weekTransactions
      .filter { it.type == TransactionType.ADJUSTMENT && it.category == "Emergency Withdrawal" }
      .sumOf { it.amountCents.coerceAtLeast(0L) }

    val releaseReserveTxs = weekTransactions.filter {
      it.type == TransactionType.ADJUSTMENT && it.category == "Reserve Release"
    }
    val releasedPriorReservesToCashThisWeek = releaseReserveTxs
      .sumOf { it.amountCents.coerceAtLeast(0L) }

    val sameWeekDebtReleased = releaseReserveTxs
      .filter { tx ->
        val res = tx.reserveId?.let { reservesById[it] }
        res != null &&
          res.weekStartDate == weekStartIso &&
          res.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE
      }
      .sumOf { it.amountCents.coerceAtLeast(0L) }

    val sameWeekEmergencyReleased = releaseReserveTxs
      .filter { tx ->
        val res = tx.reserveId?.let { reservesById[it] }
        res != null &&
          res.weekStartDate == weekStartIso &&
          res.reserveType == ReserveType.EMERGENCY_SAVINGS
      }
      .sumOf { it.amountCents.coerceAtLeast(0L) }

    val priorReservesReleased =
      (releasedPriorReservesToCashThisWeek - sameWeekDebtReleased - sameWeekEmergencyReleased)
        .coerceAtLeast(0L)
    val netDebtReservedThisWeek = (debtReservedThisWeek - sameWeekDebtReleased).coerceAtLeast(0L)
    val rawNetEmergency =
      emergencySavingsThisWeek - sameWeekEmergencyReleased - emergencyWithdrawalsToCashThisWeek
    val netEmergencySavingsThisWeek = rawNetEmergency.coerceAtLeast(0L)
    val excessEmergencyCashReturned = (-rawNetEmergency).coerceAtLeast(0L)
    val cashAddedFromReservesThisWeek = priorReservesReleased + excessEmergencyCashReturned

    val plannedFoodBudget = settings.weeklyFoodBudgetCents
    val remainingFoodBudget = plannedFoodBudget - actualFoodExpenses

    val availableSpendingMoney = recordedAllowanceThisWeek -
      actualFoodExpenses -
      actualOtherExpenses +
      emergencyFundedExpensesThisWeek -
      debtReservedThisWeek -
      emergencySavingsThisWeek -
      directRepaymentsFromCashThisWeek +
      emergencyWithdrawalsToCashThisWeek +
      releasedPriorReservesToCashThisWeek

    return WeeklyBudgetSummary(
      weekStartDate = weekStart,
      weekEndDate = weekEnd,
      recordedAllowanceCents = recordedAllowanceThisWeek,
      conservativePlannedAllowanceCents = settings.conservativeAllowanceCents,
      maxPlannedAllowanceCents = settings.maxWeeklyAllowanceCents,
      plannedFoodBudgetCents = plannedFoodBudget,
      actualFoodExpensesCents = actualFoodExpenses,
      actualOtherExpensesCents = actualOtherExpenses,
      activeDebtReserveCents = activeDebtReserveTotal,
      appliedDebtReserveCents = appliedDebtReserveTotal,
      emergencySavingsThisWeekCents = emergencySavingsThisWeek,
      repaymentsPaidThisWeekCents = repaymentsPaidThisWeek,
      directRepaymentsFromCashCents = directRepaymentsFromCashThisWeek,
      remainingFoodBudgetCents = remainingFoodBudget,
      availableSpendingMoneyCents = availableSpendingMoney,
      schoolDaysLoggedThisWeek = schoolDaysLogged,
      plannedSchoolDaysPerWeek = settings.schoolDaysPerWeek,
      emergencyFundedExpensesThisWeekCents = emergencyFundedExpensesThisWeek,
      emergencyWithdrawalsToCashThisWeekCents = emergencyWithdrawalsToCashThisWeek,
      releasedPriorReservesToCashThisWeekCents = releasedPriorReservesToCashThisWeek,
      debtReservedThisWeekCents = netDebtReservedThisWeek,
      activeDebtReserveThisWeekCents = activeDebtReserveThisWeek,
      netEmergencySavingsThisWeekCents = netEmergencySavingsThisWeek,
      cashAddedFromReservesThisWeekCents = cashAddedFromReservesThisWeek
    )
  }

  fun buildPaymentOverview(
    debts: List<DebtEntity>,
    installments: List<InstallmentEntity>,
    today: LocalDate
  ): PaymentOverview {
    val activeDebts = debts.filter {
      it.status == DebtStatus.ACTIVE && it.remainingBalanceCents > 0L && countsTowardTotalDebt(it)
    }
    val installmentsByDebt = installments.groupBy { it.debtId }

    val calendarItems = mutableListOf<NextPaymentItem>()
    val unconfirmedOrUnknown = mutableListOf<NextPaymentItem>()

    for (debt in activeDebts) {
      val unpaidInstallments = (installmentsByDebt[debt.id] ?: emptyList())
        .filter { it.status.isPayableInstallment }

      if (unpaidInstallments.isNotEmpty()) {
        for (inst in unpaidInstallments) {
          val remainingInstAmount = (inst.amountDueCents - inst.paidAmountCents).coerceAtLeast(0L)
          if (remainingInstAmount <= 0L) continue
          val urgency = ManilaTime.classifyUrgency(today, inst.dueDate, inst.dateStatus)
          val item = NextPaymentItem(
            debtId = debt.id,
            debtName = debt.name,
            lender = debt.lender,
            installmentId = inst.id,
            amountCents = remainingInstAmount,
            dueDateIso = inst.dueDate,
            dateStatus = inst.dateStatus,
            urgency = urgency,
            frequency = debt.frequency
          )
          if (inst.dueDate != null &&
            (inst.dateStatus == DueDateStatus.CONFIRMED ||
              inst.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
          ) {
            calendarItems.add(item)
          } else {
            unconfirmedOrUnknown.add(item)
          }
        }
      } else {
        // No scheduled installment rows: derive strictly from debt fields without inventing dates.
        val amount = debt.installmentAmountCents.takeIf { it > 0L } ?: debt.remainingBalanceCents
        val urgency = ManilaTime.classifyUrgency(today, debt.nextDueDate, debt.dateStatus)
        val item = NextPaymentItem(
          debtId = debt.id,
          debtName = debt.name,
          lender = debt.lender,
          installmentId = null,
          amountCents = amount,
          dueDateIso = debt.nextDueDate,
          dateStatus = debt.dateStatus,
          urgency = urgency,
          frequency = debt.frequency
        )
        if (debt.nextDueDate != null &&
          (debt.dateStatus == DueDateStatus.CONFIRMED ||
            debt.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
        ) {
          calendarItems.add(item)
        } else {
          unconfirmedOrUnknown.add(item)
        }
      }
    }

    val sortedDated = calendarItems.sortedWith(
      compareBy<NextPaymentItem> { it.dueDateIso ?: "9999-12-31" }.thenBy { it.debtId }
    )

    val nextConfirmed = sortedDated.firstOrNull { it.dateStatus == DueDateStatus.CONFIRMED }
    val nextEstimated = sortedDated.firstOrNull {
      it.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
    }

    return PaymentOverview(
      nextConfirmedPayment = nextConfirmed,
      nextEstimatedPayment = nextEstimated,
      overduePayments = sortedDated.filter { it.urgency == PaymentUrgency.OVERDUE },
      dueTodayPayments = sortedDated.filter { it.urgency == PaymentUrgency.DUE_TODAY },
      dueTomorrowPayments = sortedDated.filter { it.urgency == PaymentUrgency.DUE_TOMORROW },
      upcomingDatedPayments = sortedDated.filter { it.urgency == PaymentUrgency.UPCOMING },
      unconfirmedOrUnknownDebts = unconfirmedOrUnknown
    )
  }

  /**
   * Deterministically generates installment schedule entries from a start date.
   * Refuses to invent dates when frequency is UNKNOWN or count <= 0.
   */
  fun generateInstallmentSchedule(
    startDate: LocalDate,
    count: Int,
    installmentAmountCents: Long,
    totalRemainingCents: Long,
    frequency: RepaymentFrequency,
    customIntervalDays: Int? = null,
    dateStatus: DueDateStatus
  ): List<GeneratedInstallmentSpec> {
    if (count <= 0 || installmentAmountCents <= 0L) return emptyList()
    if (frequency == RepaymentFrequency.UNKNOWN ||
      frequency == RepaymentFrequency.IRREGULAR ||
      frequency == RepaymentFrequency.TWICE_MONTHLY
    ) return emptyList()
    if (frequency == RepaymentFrequency.CUSTOM_DAYS && (customIntervalDays == null || customIntervalDays <= 0)) {
      return emptyList()
    }

    val actualCount = if (frequency == RepaymentFrequency.ONE_TIME) 1 else count
    val result = mutableListOf<GeneratedInstallmentSpec>()
    var currentDate: LocalDate? = startDate
    var remainingToAllocate = totalRemainingCents.coerceAtLeast(0L)

    for (index in 1..actualCount) {
      val date = currentDate ?: break
      val amount = if (totalRemainingCents > 0L && index == actualCount && remainingToAllocate > 0L) {
        remainingToAllocate
      } else if (totalRemainingCents > 0L) {
        minOf(installmentAmountCents, remainingToAllocate).takeIf { it > 0L } ?: installmentAmountCents
      } else {
        installmentAmountCents
      }
      remainingToAllocate = (remainingToAllocate - amount).coerceAtLeast(0L)

      result.add(
        GeneratedInstallmentSpec(
          installmentNumber = index,
          dueDateIso = ManilaTime.formatIso(date),
          amountDueCents = amount,
          dateStatus = dateStatus
        )
      )
      currentDate = ManilaTime.advanceDate(date, frequency, customIntervalDays)
    }

    return result
  }

  fun calculateWeeklyDuesReadiness(
    today: LocalDate,
    paymentOverview: PaymentOverview,
    weeklySummary: WeeklyBudgetSummary,
    activeDebtReserveTotalCents: Long
  ): WeeklyDuesReadiness {
    val horizon = today.plusDays(7)
    val dueUpcoming7Days = paymentOverview.upcomingDatedPayments.filter { item ->
      val due = ManilaTime.parseDate(item.dueDateIso) ?: return@filter false
      !due.isAfter(horizon)
    }
    val allSoon = paymentOverview.overduePayments +
      paymentOverview.dueTodayPayments +
      paymentOverview.dueTomorrowPayments +
      dueUpcoming7Days

    val overdueCents = paymentOverview.overduePayments.sumOf { it.amountCents }
    val dueNext7DaysCents = allSoon.sumOf { it.amountCents }
    val availableCash = weeklySummary.availableSpendingMoneyCents.coerceAtLeast(0L)
    val safeReserve = activeDebtReserveTotalCents.coerceAtLeast(0L)
    val shortfallAfterReserve = (dueNext7DaysCents - safeReserve).coerceAtLeast(0L)
    val unmetShortfall = (dueNext7DaysCents - (safeReserve + availableCash)).coerceAtLeast(0L)

    val status = when {
      dueNext7DaysCents <= 0L -> DuesCoverageStatus.NO_DUES
      safeReserve >= dueNext7DaysCents -> DuesCoverageStatus.COVERED_BY_RESERVE
      safeReserve + availableCash >= dueNext7DaysCents -> DuesCoverageStatus.COVERED_WITH_CASH
      else -> DuesCoverageStatus.SHORTFALL
    }

    return WeeklyDuesReadiness(
      dueNext7DaysCents = dueNext7DaysCents,
      overdueCents = overdueCents,
      dueNext7DaysCount = allSoon.size,
      activeDebtReserveCents = safeReserve,
      availableCashCents = availableCash,
      shortfallAfterReserveCents = shortfallAfterReserve,
      unmetShortfallCents = unmetShortfall,
      status = status
    )
  }

  fun calculatePayoffInsights(
    debts: List<DebtEntity>,
    installments: List<InstallmentEntity>,
    paymentOverview: PaymentOverview,
    today: LocalDate
  ): PayoffProjectionSummary {
    val activeDebts = debts.filter {
      it.status == DebtStatus.ACTIVE && it.remainingBalanceCents > 0L && countsTowardTotalDebt(it)
    }
    val installmentsByDebt = installments.groupBy { it.debtId }

    var includedCount = 0
    var excludedCount = 0
    var includedTotalCents = 0L
    var includesEstimated = false
    var latestCompletionDate: LocalDate? = null

    for (debt in activeDebts) {
      val unpaidInst = (installmentsByDebt[debt.id] ?: emptyList())
        .filter { it.status.isPayableInstallment }

      var debtCompletionDate: LocalDate? = null
      var debtEstimated = false

      if (unpaidInst.isNotEmpty()) {
        val allDated = unpaidInst.all {
          ManilaTime.parseDate(it.dueDate) != null &&
            (it.dateStatus == DueDateStatus.CONFIRMED || it.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
        }
        if (allDated && debt.frequency != RepaymentFrequency.UNKNOWN) {
          debtCompletionDate = unpaidInst.mapNotNull { ManilaTime.parseDate(it.dueDate) }.maxOrNull()
          debtEstimated = unpaidInst.any { it.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION }
        }
      } else if (
        debt.frequency != RepaymentFrequency.UNKNOWN &&
        debt.frequency != RepaymentFrequency.IRREGULAR &&
        debt.frequency != RepaymentFrequency.TWICE_MONTHLY &&
        (debt.dateStatus == DueDateStatus.CONFIRMED || debt.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION)
      ) {
        val firstDue = ManilaTime.parseDate(debt.nextDueDate)
        if (firstDue != null) {
          debtEstimated = debt.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
          if (debt.frequency == RepaymentFrequency.ONE_TIME) {
            debtCompletionDate = firstDue
          } else if (debt.installmentAmountCents > 0L) {
            val count = maxOf(
              debt.remainingInstallmentsCount,
              MoneyFormatter.divideCentsCeil(debt.remainingBalanceCents, debt.installmentAmountCents).toInt()
            ).coerceAtLeast(1)
            var current: LocalDate? = firstDue
            repeat(count - 1) {
              current = current?.let { ManilaTime.advanceDate(it, debt.frequency, debt.customIntervalDays) }
            }
            debtCompletionDate = current
          }
        }
      }

      if (debtCompletionDate != null) {
        includedCount++
        includedTotalCents += debt.remainingBalanceCents
        if (debtEstimated) includesEstimated = true
        if (latestCompletionDate == null || debtCompletionDate.isAfter(latestCompletionDate)) {
          latestCompletionDate = debtCompletionDate
        }
      } else {
        excludedCount++
      }
    }

    val weeksRemaining = latestCompletionDate?.let { end ->
      val days = ChronoUnit.DAYS.between(today, end).coerceAtLeast(0L)
      if (days == 0L) 0 else ((days + 6L) / 7L).toInt().coerceAtLeast(1)
    }

    val quickestWin = activeDebts
      .minByOrNull { it.remainingBalanceCents }
      ?.let {
        PayoffDebtCandidate(
          debtId = it.id,
          name = it.name,
          lender = it.lender,
          remainingBalanceCents = it.remainingBalanceCents,
          installmentAmountCents = it.installmentAmountCents,
          frequency = it.frequency
        )
      }

    val soonestDue = paymentOverview.overduePayments.firstOrNull()
      ?: paymentOverview.dueTodayPayments.firstOrNull()
      ?: paymentOverview.dueTomorrowPayments.firstOrNull()
      ?: paymentOverview.upcomingDatedPayments.firstOrNull()

    return PayoffProjectionSummary(
      includedDebtsCount = includedCount,
      excludedDebtsCount = excludedCount,
      includedTotalBalanceCents = includedTotalCents,
      estimatedDebtFreeDateIso = latestCompletionDate?.let { ManilaTime.formatIso(it) },
      estimatedWeeksRemaining = weeksRemaining,
      includesEstimatedDates = includesEstimated,
      quickestWinDebt = quickestWin,
      soonestDueDebt = soonestDue
    )
  }

  fun calculateWeeklyTrends(
    today: LocalDate,
    allowances: List<AllowanceRecordEntity>,
    expenses: List<ExpenseEntity>,
    transactions: List<TransactionEntity>,
    weeksCount: Int = 4
  ): List<WeeklyTrendPoint> {
    val safeCount = weeksCount.coerceIn(1, 8)
    val currentWeekStart = ManilaTime.weekStart(today)
    return (safeCount - 1 downTo 0).map { offset ->
      val start = currentWeekStart.minusWeeks(offset.toLong())
      val end = start.plusDays(6)
      val label = when (offset) {
        0 -> "This week"
        1 -> "Last week"
        else -> ManilaTime.formatShort(ManilaTime.formatIso(start))
      }
      val allowanceCents = allowances
        .filter { ManilaTime.isInWeek(it.receivedDate, start) }
        .sumOf { it.amountCents }
      val expensesCents = expenses
        .filter { ManilaTime.isInWeek(it.date, start) }
        .sumOf { it.amountCents }
      val repaymentsCents = transactions
        .filter { !it.isVoided && it.type == TransactionType.DEBT_REPAYMENT && ManilaTime.isInWeek(it.date, start) }
        .sumOf { it.amountCents }

      WeeklyTrendPoint(
        weekStartDate = start,
        weekEndDate = end,
        label = label,
        allowanceCents = allowanceCents,
        expensesCents = expensesCents,
        repaymentsCents = repaymentsCents
      )
    }
  }

  fun calculatePriorWeekCarryOverCandidate(
    today: LocalDate,
    settings: UserSettingsEntity,
    allowances: List<AllowanceRecordEntity>,
    expenses: List<ExpenseEntity>,
    reserves: List<DebtReserveEntity>,
    transactions: List<TransactionEntity>,
    resolvedWeekIso: String?
  ): PriorWeekCarryOverCandidate? {
    val prevWeekStart = ManilaTime.weekStart(today).minusWeeks(1)
    val prevWeekIso = ManilaTime.formatIso(prevWeekStart)
    if (resolvedWeekIso == prevWeekIso) return null

    val prevSummary = calculateWeeklyBudgetSummary(
      today = prevWeekStart,
      settings = settings,
      allowances = allowances,
      expenses = expenses,
      reserves = reserves,
      transactions = transactions
    )
    if (prevSummary.recordedAllowanceCents <= 0L || prevSummary.availableSpendingMoneyCents <= 0L) {
      return null
    }
    return PriorWeekCarryOverCandidate(
      weekStartDate = prevWeekStart,
      weekEndDate = prevWeekStart.plusDays(6),
      weekStartIso = prevWeekIso,
      leftoverCents = prevSummary.availableSpendingMoneyCents
    )
  }
}
