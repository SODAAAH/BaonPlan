package com.baonplan.finance.domain.budget

import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import java.time.LocalDate

/**
 * Half-open date window `[startDate, endDateExclusive)`.
 */
data class BudgetPeriodWindow(
  val periodOffset: Int,
  val startDate: LocalDate,
  val endDateExclusive: LocalDate,
  val label: String,
  val frequencyLabel: String
) {
  val endDateInclusive: LocalDate
    get() = endDateExclusive.minusDays(1)

  operator fun contains(date: LocalDate): Boolean =
    !date.isBefore(startDate) && date.isBefore(endDateExclusive)
}

data class ResolvedIncomeOccurrence(
  val occurrenceId: Long?,
  val sourceId: Long?,
  val sourceName: String,
  val expectedDate: LocalDate,
  val expectedAmountCents: Long,
  val actualReceivedAmountCents: Long,
  val actualReceivedDate: LocalDate?,
  val status: IncomeOccurrenceStatus,
  val isVariableAmount: Boolean,
  val isFromPrimarySource: Boolean,
  val linkedAllowanceRecordId: Long?,
  val notes: String = ""
)

data class BudgetDebtObligationItem(
  val debtId: Long,
  val installmentId: Long?,
  val debtName: String,
  val providerName: String,
  val classification: LoanProductClassification,
  val dueDate: LocalDate?,
  val dueDateIso: String?,
  val dateStatus: DueDateStatus,
  val installmentStatus: InstallmentStatus?,
  val amountDueCents: Long,
  val paidAmountCents: Long,
  val unpaidAmountCents: Long,
  val isAmountKnown: Boolean,
  val isOverdue: Boolean,
  val isPendingVerification: Boolean
)

data class BudgetCategorySummary(
  val categoryKey: BudgetCategoryKey,
  val label: String,
  val allocatedCents: Long,
  val actualSpentCents: Long,
  val remainingAllocationCents: Long,
  val isOverspent: Boolean
)

data class UpcomingReservePlan(
  val debtId: Long,
  val debtName: String,
  val dueDate: LocalDate,
  val dueDateIso: String,
  val totalDueCents: Long,
  val existingReservedForDebtCents: Long,
  val remainingToReserveCents: Long,
  val remainingCyclesBeforeDue: Int,
  val recommendedThisCycleCents: Long
)

data class BudgetOverviewSummary(
  val isConfigured: Boolean,
  val periodWindow: BudgetPeriodWindow,
  val periodOffset: Int,
  val openingCarryoverCents: Long,
  val rolloverEnabled: Boolean,
  val expectedIncomeCents: Long,
  val pendingExpectedIncomeCents: Long,
  val actualReceivedIncomeCents: Long,
  val unreceivedConfirmedDeltaCents: Long,
  val periodDebtTotalCents: Long,
  val periodDebtItems: List<BudgetDebtObligationItem>,
  val overdueDebtTotalCents: Long,
  val overdueDebtItems: List<BudgetDebtObligationItem>,
  val debtCoveredByExistingReserveCents: Long,
  val actualExpensesCents: Long,
  val emergencyFundedExpensesCents: Long,
  val directCashRepaymentsCents: Long,
  val netDebtReservedInPeriodCents: Long,
  val netEmergencySavedInPeriodCents: Long,
  val totalActiveDebtReserveCents: Long,
  val categoryAllocations: List<BudgetCategorySummary>,
  val totalPlannedCategoryRemainingCents: Long,
  val plannedFutureReservesThisCycleCents: Long,
  val upcomingReservePlans: List<UpcomingReservePlan>,
  val projectedRemainingCents: Long,
  val projectedRemainingAfterOverdueCents: Long,
  val actualRemainingCents: Long,
  val actualAfterUnpaidDebtCents: Long,
  val availableAfterDebtCents: Long,
  val isProjectedDeficit: Boolean,
  val isActualDeficit: Boolean,
  val hasUnknownAmountObligations: Boolean,
  val hasEstimatedObligations: Boolean,
  val hasOverdueObligations: Boolean,
  val hasPendingExpectedReceipts: Boolean,
  val nextExpectedIncomeDate: LocalDate?,
  val nextExpectedIncomeAmountCents: Long?,
  val nextExpectedIncomeSourceName: String?,
  val occurrencesInPeriod: List<ResolvedIncomeOccurrence>,
  val activeSourcesCount: Int,
  val pausedSourcesCount: Int,
  val hasUnconfirmedDateObligations: Boolean,
  val unconfirmedDateObligationsCount: Int
)

data class FrequencyNormalizationItem(
  val sourceId: Long,
  val name: String,
  val frequency: IncomeFrequency,
  val perOccurrenceCents: Long,
  val normalizedMonthlyCents: Long,
  val normalizedAnnualCents: Long
)

data class FrequencyNormalizationSummary(
  val items: List<FrequencyNormalizationItem>,
  val totalNormalizedMonthlyCents: Long,
  val totalNormalizedAnnualCents: Long
)

data class GroupedPaymentItem(
  val debtId: Long,
  val installmentId: Long?,
  val debtName: String,
  val providerName: String,
  val classification: LoanProductClassification,
  val dueDate: LocalDate?,
  val dueDateIso: String?,
  val dateStatus: DueDateStatus,
  val installmentStatus: InstallmentStatus?,
  val unpaidAmountCents: Long?,
  val totalDueCents: Long?,
  val paidAmountCents: Long,
  val isAmountKnown: Boolean,
  val isEstimatedDate: Boolean,
  val isPending: Boolean,
  val isOverdue: Boolean
)

data class PaymentDueGroup(
  val startDate: LocalDate,
  val endDate: LocalDate,
  val isSingleDate: Boolean,
  val isOverdueGroup: Boolean,
  val dateRangeLabel: String,
  val headlineLabel: String,
  val countdownLabel: String,
  val items: List<GroupedPaymentItem>,
  val knownTotalCents: Long,
  val totalPaymentsCount: Int,
  val distinctLoansCount: Int,
  val hasIncompleteAmounts: Boolean,
  val hasEstimatedDates: Boolean,
  val hasPendingPayments: Boolean,
  val primaryDebtId: Long
) {
  val isMultiItemGroup: Boolean
    get() = items.size > 1
}

data class PaymentGroupingOverview(
  val overdueGroup: PaymentDueGroup?,
  val nextGroup: PaymentDueGroup?,
  val followingGroups: List<PaymentDueGroup>,
  val undatedOrUnconfirmedPayments: List<GroupedPaymentItem>,
  val hasAnyActionablePayments: Boolean
) {
  val totalUpcomingGroupsCount: Int
    get() = (if (nextGroup != null) 1 else 0) + followingGroups.size
}
