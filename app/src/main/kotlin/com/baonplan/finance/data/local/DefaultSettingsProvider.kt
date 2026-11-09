package com.baonplan.finance.data.local

import com.baonplan.finance.data.local.entity.UserSettingsEntity

object DefaultSettingsProvider {

  fun defaultSettings(): UserSettingsEntity = UserSettingsEntity(
    id = 1,
    minWeeklyAllowanceCents = 40_000L,
    maxWeeklyAllowanceCents = 50_000L,
    conservativeAllowanceCents = 40_000L,
    schoolDaysPerWeek = 2,
    foodPerSchoolDayCents = 5_000L,
    weeklyFoodBudgetCents = 10_000L,
    weeklyOtherExpensesBudgetCents = 5_000L,
    weeklyEmergencySavingsTargetCents = 5_000L,
    weeklyDebtReserveTargetCents = 15_000L,
    remindersEnabled = true,
    allowanceReminderDayOfWeek = 1,
    reminderHour = 8,
    reminderMinute = 0,
    isManuallyEntered = false
  )
}
