package com.baonplan.finance.domain.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class ExpensePreset(
  val id: String,
  val label: String,
  val amountCents: Long,
  val category: ExpenseCategory,
  val isSchoolDay: Boolean
)

enum class DuesCoverageStatus {
  NO_DUES,
  COVERED_BY_RESERVE,
  COVERED_WITH_CASH,
  SHORTFALL
}

data class WeeklyDuesReadiness(
  val dueNext7DaysCents: Long,
  val overdueCents: Long,
  val dueNext7DaysCount: Int,
  val activeDebtReserveCents: Long,
  val availableCashCents: Long,
  val shortfallAfterReserveCents: Long,
  val unmetShortfallCents: Long,
  val status: DuesCoverageStatus
)

data class WeeklyTrendPoint(
  val weekStartDate: LocalDate,
  val weekEndDate: LocalDate,
  val label: String,
  val allowanceCents: Long,
  val expensesCents: Long,
  val repaymentsCents: Long
)

data class PriorWeekCarryOverCandidate(
  val weekStartDate: LocalDate,
  val weekEndDate: LocalDate,
  val weekStartIso: String,
  val leftoverCents: Long
)

data class PayoffDebtCandidate(
  val debtId: Long,
  val name: String,
  val lender: String,
  val remainingBalanceCents: Long,
  val installmentAmountCents: Long,
  val frequency: RepaymentFrequency
)

data class PayoffProjectionSummary(
  val includedDebtsCount: Int,
  val excludedDebtsCount: Int,
  val includedTotalBalanceCents: Long,
  val estimatedDebtFreeDateIso: String?,
  val estimatedWeeksRemaining: Int?,
  val includesEstimatedDates: Boolean,
  val quickestWinDebt: PayoffDebtCandidate?,
  val soonestDueDebt: NextPaymentItem?
)

data class LoanCostBreakdown(
  val originalPrincipalCents: Long?,
  val amountReceivedCents: Long?,
  val interestCents: Long?,
  val serviceFeeCents: Long?,
  val processingFeeCents: Long?,
  val totalFeesAndInterestCents: Long?,
  val totalScheduledRepaymentCents: Long?,
  val priorPaidCents: Long,
  val isPriorPaidKnown: Boolean,
  val trackedPaidCents: Long,
  val totalKnownPaidCents: Long,
  val remainingBalanceCents: Long
)

data class ScenarioPlan(
  val scenarioName: String,
  val allowanceCents: Long,
  val foodBudgetCents: Long,
  val remainingAfterFoodCents: Long,
  val otherExpensesCents: Long,
  val recommendedSafeDebtReserveCents: Long,
  val plannedDebtReserveCents: Long,
  val emergencySavingsCents: Long,
  val remainingSpendingMoneyCents: Long,
  val isDeficit: Boolean,
  val safeDebtCapReason: String
)

data class WeeklyBudgetSummary(
  val weekStartDate: LocalDate,
  val weekEndDate: LocalDate,
  val recordedAllowanceCents: Long,
  val conservativePlannedAllowanceCents: Long,
  val maxPlannedAllowanceCents: Long,
  val plannedFoodBudgetCents: Long,
  val actualFoodExpensesCents: Long,
  val actualOtherExpensesCents: Long,
  val activeDebtReserveCents: Long,
  val appliedDebtReserveCents: Long,
  val emergencySavingsThisWeekCents: Long,
  val repaymentsPaidThisWeekCents: Long,
  val directRepaymentsFromCashCents: Long,
  val remainingFoodBudgetCents: Long,
  val availableSpendingMoneyCents: Long,
  val schoolDaysLoggedThisWeek: Int = 0,
  val plannedSchoolDaysPerWeek: Int = 0,
  val emergencyFundedExpensesThisWeekCents: Long = 0L,
  val emergencyWithdrawalsToCashThisWeekCents: Long = 0L,
  val releasedPriorReservesToCashThisWeekCents: Long = 0L
)

data class NextPaymentItem(
  val debtId: Long,
  val debtName: String,
  val lender: String,
  val installmentId: Long?,
  val amountCents: Long,
  val dueDateIso: String?,
  val dateStatus: DueDateStatus,
  val urgency: PaymentUrgency,
  val frequency: RepaymentFrequency
)

data class PaymentOverview(
  val nextConfirmedPayment: NextPaymentItem?,
  val nextEstimatedPayment: NextPaymentItem?,
  val overduePayments: List<NextPaymentItem>,
  val dueTodayPayments: List<NextPaymentItem>,
  val dueTomorrowPayments: List<NextPaymentItem>,
  val upcomingDatedPayments: List<NextPaymentItem>,
  val unconfirmedOrUnknownDebts: List<NextPaymentItem>
)

data class RepaymentProgressSummary(
  val totalActiveDebtCents: Long,
  val completedLoansCount: Int,
  val activeLoansCount: Int,
  val archivedLoansCount: Int,
  val completedLoansOriginalTotalCents: Long,
  val trackedRepaidOnActiveCents: Long,
  val totalKnownRepaidCents: Long,
  val progressFraction: Float
)

data class GeneratedInstallmentSpec(
  val installmentNumber: Int,
  val dueDateIso: String,
  val amountDueCents: Long,
  val dateStatus: DueDateStatus
)
