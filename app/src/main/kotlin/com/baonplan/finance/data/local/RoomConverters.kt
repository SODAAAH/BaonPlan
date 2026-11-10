package com.baonplan.finance.data.local

import androidx.room.TypeConverter
import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.BudgetPeriodStyle
import com.baonplan.finance.domain.model.DebtDeductionMode
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.ObligationRole
import com.baonplan.finance.domain.model.ProjectionReliability
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ScheduleCompletenessStatus
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.model.TwiceMonthlyPattern
import com.baonplan.finance.domain.model.WeeklyCycleMode

class RoomConverters {
  @TypeConverter fun fromLoanProductClassification(v: LoanProductClassification): String = v.name
  @TypeConverter fun toLoanProductClassification(v: String): LoanProductClassification =
    runCatching { LoanProductClassification.valueOf(v) }.getOrDefault(LoanProductClassification.UNKNOWN)

  @TypeConverter fun fromObligationRole(v: ObligationRole): String = v.name
  @TypeConverter fun toObligationRole(v: String): ObligationRole =
    runCatching { ObligationRole.valueOf(v) }.getOrDefault(ObligationRole.STANDALONE)

  @TypeConverter fun fromRepaymentFrequency(v: RepaymentFrequency): String = v.name
  @TypeConverter fun toRepaymentFrequency(v: String): RepaymentFrequency =
    runCatching { RepaymentFrequency.valueOf(v) }.getOrDefault(RepaymentFrequency.UNKNOWN)

  @TypeConverter fun fromDueDateStatus(v: DueDateStatus): String = v.name
  @TypeConverter fun toDueDateStatus(v: String): DueDateStatus = DueDateStatus.valueOf(v)

  @TypeConverter fun fromDebtStatus(v: DebtStatus): String = v.name
  @TypeConverter fun toDebtStatus(v: String): DebtStatus = DebtStatus.valueOf(v)

  @TypeConverter fun fromInstallmentStatus(v: InstallmentStatus): String = v.name
  @TypeConverter fun toInstallmentStatus(v: String): InstallmentStatus =
    runCatching { InstallmentStatus.valueOf(v) }.getOrDefault(InstallmentStatus.UNKNOWN)

  @TypeConverter fun fromTransactionType(v: TransactionType): String = v.name
  @TypeConverter fun toTransactionType(v: String): TransactionType = TransactionType.valueOf(v)

  @TypeConverter fun fromExpenseCategory(v: ExpenseCategory): String = v.name
  @TypeConverter fun toExpenseCategory(v: String): ExpenseCategory =
    runCatching { ExpenseCategory.valueOf(v) }.getOrDefault(ExpenseCategory.OTHER)

  @TypeConverter fun fromReserveType(v: ReserveType): String = v.name
  @TypeConverter fun toReserveType(v: String): ReserveType = ReserveType.valueOf(v)

  @TypeConverter fun fromReserveStatus(v: ReserveStatus): String = v.name
  @TypeConverter fun toReserveStatus(v: String): ReserveStatus = ReserveStatus.valueOf(v)

  @TypeConverter fun fromScheduleCompletenessStatus(v: ScheduleCompletenessStatus): String = v.name
  @TypeConverter fun toScheduleCompletenessStatus(v: String): ScheduleCompletenessStatus =
    runCatching { ScheduleCompletenessStatus.valueOf(v) }.getOrDefault(ScheduleCompletenessStatus.NEEDS_USER_REVIEW)

  @TypeConverter fun fromProjectionReliability(v: ProjectionReliability): String = v.name
  @TypeConverter fun toProjectionReliability(v: String): ProjectionReliability =
    runCatching { ProjectionReliability.valueOf(v) }.getOrDefault(ProjectionReliability.INSUFFICIENT_DATA)

  @TypeConverter fun fromIncomeFrequency(v: IncomeFrequency): String = v.name
  @TypeConverter fun toIncomeFrequency(v: String): IncomeFrequency =
    runCatching { IncomeFrequency.valueOf(v) }.getOrDefault(IncomeFrequency.WEEKLY)

  @TypeConverter fun fromWeeklyCycleMode(v: WeeklyCycleMode): String = v.name
  @TypeConverter fun toWeeklyCycleMode(v: String): WeeklyCycleMode =
    runCatching { WeeklyCycleMode.valueOf(v) }.getOrDefault(WeeklyCycleMode.ANCHOR_ALIGNED)

  @TypeConverter fun fromMonthlyCycleMode(v: MonthlyCycleMode): String = v.name
  @TypeConverter fun toMonthlyCycleMode(v: String): MonthlyCycleMode =
    runCatching { MonthlyCycleMode.valueOf(v) }.getOrDefault(MonthlyCycleMode.CALENDAR_MONTH)

  @TypeConverter fun fromTwiceMonthlyPattern(v: TwiceMonthlyPattern): String = v.name
  @TypeConverter fun toTwiceMonthlyPattern(v: String): TwiceMonthlyPattern =
    runCatching { TwiceMonthlyPattern.valueOf(v) }.getOrDefault(TwiceMonthlyPattern.FIRST_AND_FIFTEENTH)

  @TypeConverter fun fromBudgetPeriodStyle(v: BudgetPeriodStyle): String = v.name
  @TypeConverter fun toBudgetPeriodStyle(v: String): BudgetPeriodStyle =
    runCatching { BudgetPeriodStyle.valueOf(v) }.getOrDefault(BudgetPeriodStyle.AUTO_FROM_PRIMARY)

  @TypeConverter fun fromIncomeOccurrenceStatus(v: IncomeOccurrenceStatus): String = v.name
  @TypeConverter fun toIncomeOccurrenceStatus(v: String): IncomeOccurrenceStatus =
    runCatching { IncomeOccurrenceStatus.valueOf(v) }.getOrDefault(IncomeOccurrenceStatus.EXPECTED)

  @TypeConverter fun fromDebtDeductionMode(v: DebtDeductionMode): String = v.name
  @TypeConverter fun toDebtDeductionMode(v: String): DebtDeductionMode =
    runCatching { DebtDeductionMode.valueOf(v) }.getOrDefault(DebtDeductionMode.UNPAID_ONLY)

  @TypeConverter fun fromBudgetCategoryKey(v: BudgetCategoryKey): String = v.name
  @TypeConverter fun toBudgetCategoryKey(v: String): BudgetCategoryKey =
    runCatching { BudgetCategoryKey.valueOf(v) }.getOrDefault(BudgetCategoryKey.OTHER)
}
