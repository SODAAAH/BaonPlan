package com.baonplan.finance.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// 4 primary destinations
@Serializable data object DashboardKey : NavKey
@Serializable data object AllowanceBudgetKey : NavKey
@Serializable data object DebtsKey : NavKey
@Serializable data object MoreKey : NavKey

// Secondary destinations accessed from More or item detail flows
@Serializable data object CalendarKey : NavKey
@Serializable data object TransactionsKey : NavKey
@Serializable data object ReportsKey : NavKey
@Serializable data object AppearanceKey : NavKey
@Serializable data object NotificationsKey : NavKey
@Serializable data object BudgetDefaultsKey : NavKey
@Serializable data object BackupKey : NavKey
@Serializable data object AboutKey : NavKey
@Serializable data class DebtDetailKey(val debtId: Long) : NavKey
