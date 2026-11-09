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
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.GeneratedInstallmentSpec
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanCostBreakdown
import com.baonplan.finance.domain.model.NextPaymentItem
import com.baonplan.finance.domain.model.PaymentOverview
import com.baonplan.finance.domain.model.PaymentUrgency
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.RepaymentProgressSummary
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ScenarioPlan
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.model.WeeklyBudgetSummary
import com.baonplan.finance.domain.util.ManilaTime
import java.time.LocalDate

object FinancialCalculator {

  fun calculateTotalActiveDebt(debts: List<DebtEntity>): Long =
    debts.filter { it.status == DebtStatus.ACTIVE }
      .sumOf { it.remainingBalanceCents.coerceAtLeast(0L) }

  fun calculateRepaymentProgress(debts: List<DebtEntity>): RepaymentProgressSummary {
    val active = debts.filter { it.status == DebtStatus.ACTIVE }
    val completed = debts.filter { it.status == DebtStatus.PAID }
    val archived = debts.filter { it.status == DebtStatus.ARCHIVED }

    val totalActiveDebt = active.sumOf { it.remainingBalanceCents.coerceAtLeast(0L) }
    val completedOriginal = completed.sumOf {
      (it.totalScheduledRepaymentCents ?: it.originalPrincipalCents ?: it.priorPaidCents)
        .coerceAtLeast(0L)
    }
    val trackedRepaidOnActive = active.sumOf { it.trackedPaidCents.coerceAtLeast(0L) }
    val knownPaidOnAll = debts.sumOf {
      (it.priorPaidCents + it.trackedPaidCents).coerceAtLeast(0L)
    }

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

    // Active reserved debt money across all weeks (not yet paid as a debt repayment)
    val activeDebtReserveTotal = reserves
      .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE && it.status == ReserveStatus.RESERVED }
      .sumOf { it.remainingReservedCents }

    val appliedDebtReserveTotal = reserves
      .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE }
      .sumOf { it.appliedAmountCents }

    // Cash set aside THIS week into reserves or emergency savings (excluding released reserves)
    val debtReservedThisWeek = reserves
      .filter {
        it.weekStartDate == weekStartIso &&
          it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE &&
          it.status != ReserveStatus.RELEASED
      }
      .sumOf { it.amountCents }

    val emergencySavingsThisWeek = reserves
      .filter {
        it.weekStartDate == weekStartIso &&
          it.reserveType == ReserveType.EMERGENCY_SAVINGS &&
          it.status != ReserveStatus.RELEASED
      }
      .sumOf { it.amountCents }

    val weekRepayments = transactions.filter {
      !it.isVoided &&
        it.type == TransactionType.DEBT_REPAYMENT &&
        ManilaTime.isInWeek(it.date, weekStart)
    }

    val repaymentsPaidThisWeek = weekRepayments.sumOf { it.amountCents }
    val directRepaymentsFromCashThisWeek = weekRepayments.sumOf {
      (it.amountCents - it.fundedFromReserveCents).coerceAtLeast(0L)
    }

    val plannedFoodBudget = settings.weeklyFoodBudgetCents
    val remainingFoodBudget = plannedFoodBudget - actualFoodExpenses

    val availableSpendingMoney = recordedAllowanceThisWeek -
      actualFoodExpenses -
      actualOtherExpenses -
      debtReservedThisWeek -
      emergencySavingsThisWeek -
      directRepaymentsFromCashThisWeek

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
      availableSpendingMoneyCents = availableSpendingMoney
    )
  }

  fun buildPaymentOverview(
    debts: List<DebtEntity>,
    installments: List<InstallmentEntity>,
    today: LocalDate
  ): PaymentOverview {
    val activeDebts = debts.filter { it.status == DebtStatus.ACTIVE && it.remainingBalanceCents > 0L }
    val installmentsByDebt = installments.groupBy { it.debtId }

    val calendarItems = mutableListOf<NextPaymentItem>()
    val unconfirmedOrUnknown = mutableListOf<NextPaymentItem>()

    for (debt in activeDebts) {
      val unpaidInstallments = (installmentsByDebt[debt.id] ?: emptyList())
        .filter { it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL }

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
    if (frequency == RepaymentFrequency.UNKNOWN) return emptyList()
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
}
