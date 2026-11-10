package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.BudgetPeriodStyle
import com.baonplan.finance.domain.model.DebtDeductionMode
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.WeeklyCycleMode
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "budget_profiles")
data class BudgetProfileEntity(
  @PrimaryKey
  val id: Int = 1,
  val isConfigured: Boolean = false,
  val periodStyle: BudgetPeriodStyle = BudgetPeriodStyle.AUTO_FROM_PRIMARY,
  val customPeriodDays: Int? = null,
  val weeklyCycleMode: WeeklyCycleMode = WeeklyCycleMode.ANCHOR_ALIGNED,
  val monthlyCycleMode: MonthlyCycleMode = MonthlyCycleMode.CALENDAR_MONTH,
  val rolloverEnabled: Boolean = true,
  val reserveAheadEnabled: Boolean = false,
  val includeExpectedIncomeInProjection: Boolean = true,
  val includeOverdueInBudget: Boolean = true,
  val debtDeductionMode: DebtDeductionMode = DebtDeductionMode.UNPAID_ONLY,
  val primarySourceId: Long? = null,
  val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
