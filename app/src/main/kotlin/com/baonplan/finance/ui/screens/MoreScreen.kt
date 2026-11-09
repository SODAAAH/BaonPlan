package com.baonplan.finance.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.BaonListRow
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel

@Composable
fun MoreScreen(
  state: BaonPlanUiState,
  hasNotificationPermission: Boolean,
  onOpenCalendar: () -> Unit,
  onOpenTransactions: () -> Unit,
  onOpenReports: () -> Unit,
  onOpenAppearance: () -> Unit,
  onOpenNotifications: () -> Unit,
  onOpenBudgetDefaults: () -> Unit,
  onOpenBackup: () -> Unit,
  onOpenAbout: () -> Unit,
  modifier: Modifier = Modifier
) {
  val notifSummary = when {
    !state.settings.remindersEnabled -> "Off"
    !hasNotificationPermission -> "Permission needed"
    else -> "On"
  }
  val budgetSummary =
    "${MoneyFormatter.formatPesoCompact(state.settings.minWeeklyAllowanceCents)}–${MoneyFormatter.formatPesoCompact(state.settings.maxWeeklyAllowanceCents)} · ${state.settings.schoolDaysPerWeek} school days"

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Column {
        Text(
          text = "More",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Activity, settings, and local data",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Group 1: YOUR ACTIVITY
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel("Your activity")
        BaonCard(contentPadding = 0.dp) {
          BaonListRow(
            title = "Payment calendar",
            subtitle = "Upcoming dues and monthly view",
            icon = Icons.Outlined.CalendarMonth,
            onClick = onOpenCalendar
          )
          CalmDivider()
          BaonListRow(
            title = "Transaction history",
            subtitle = "${state.transactions.count { !it.isVoided }} active entries",
            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
            onClick = onOpenTransactions
          )
          CalmDivider()
          BaonListRow(
            title = "Reports",
            subtitle = "Debt breakdown and CSV export",
            icon = Icons.Outlined.BarChart,
            onClick = onOpenReports
          )
        }
      }
    }

    // Group 2: APP SETTINGS
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel("App settings")
        BaonCard(contentPadding = 0.dp) {
          BaonListRow(
            title = "Appearance",
            trailingValue = state.preferences.themeMode.label,
            trailingValueColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Outlined.DarkMode,
            onClick = onOpenAppearance
          )
          CalmDivider()
          BaonListRow(
            title = "Notifications",
            trailingValue = notifSummary,
            trailingValueColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Outlined.Notifications,
            onClick = onOpenNotifications
          )
          CalmDivider()
          BaonListRow(
            title = "Budget defaults",
            subtitle = budgetSummary,
            icon = Icons.Outlined.Tune,
            onClick = onOpenBudgetDefaults
          )
          CalmDivider()
          BaonListRow(
            title = "Backup and restore",
            subtitle = "Export JSON, CSV, or clear records",
            icon = Icons.Outlined.SaveAlt,
            onClick = onOpenBackup
          )
        }
      }
    }

    // Group 3: ABOUT
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel("About")
        BaonCard(contentPadding = 0.dp) {
          BaonListRow(
            title = "About BaonPlan",
            subtitle = "PHP · Asia/Manila · Version 1.0",
            icon = Icons.Outlined.Info,
            onClick = onOpenAbout
          )
          CalmDivider()
          BaonListRow(
            title = "Privacy information",
            subtitle = "100% offline · No data shared",
            icon = Icons.Outlined.Lock,
            onClick = onOpenAbout
          )
        }
      }
    }
  }
}
