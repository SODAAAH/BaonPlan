package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
  @PrimaryKey
  val id: Int = 1,
  val minWeeklyAllowanceCents: Long = 40_000L,
  val maxWeeklyAllowanceCents: Long = 50_000L,
  val conservativeAllowanceCents: Long = 40_000L,
  val schoolDaysPerWeek: Int = 2,
  val foodPerSchoolDayCents: Long = 5_000L,
  val weeklyFoodBudgetCents: Long = 10_000L,
  val weeklyOtherExpensesBudgetCents: Long = 5_000L,
  val weeklyEmergencySavingsTargetCents: Long = 5_000L,
  val weeklyDebtReserveTargetCents: Long = 15_000L,
  val remindersEnabled: Boolean = true,
  val allowanceReminderDayOfWeek: Int = 1,
  val reminderHour: Int = 8,
  val reminderMinute: Int = 0,
  val isManuallyEntered: Boolean = true
)
