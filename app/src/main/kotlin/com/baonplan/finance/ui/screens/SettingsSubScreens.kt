package com.baonplan.finance.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.ThemeMode
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.BaonPlanUiState
import com.baonplan.finance.ui.components.BadgeTone
import com.baonplan.finance.ui.components.BaonCard
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.CalmSectionLabel
import com.baonplan.finance.ui.components.InfoCallout
import com.baonplan.finance.ui.components.MetricRow
import com.baonplan.finance.ui.components.StatusBadge
import com.baonplan.finance.ui.theme.LocalBaonColors

@Composable
fun AppearanceScreen(
  selectedMode: ThemeMode,
  onSelectMode: (ThemeMode) -> Unit,
  modifier: Modifier = Modifier
) {
  val semantic = LocalBaonColors.current
  val descriptions = mapOf(
    ThemeMode.SYSTEM to "Follow your Android device display setting",
    ThemeMode.LIGHT to "Soft warm-white surfaces with dark text",
    ThemeMode.DARK to "Deep charcoal surfaces with soft light text"
  )

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel("Theme")
        BaonCard(contentPadding = 0.dp) {
          ThemeMode.entries.forEachIndexed { index, mode ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectMode(mode) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                  text = mode.label,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = descriptions[mode] ?: "",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              RadioButton(
                selected = selectedMode == mode,
                onClick = { onSelectMode(mode) }
              )
            }
            if (index < ThemeMode.entries.lastIndex) {
              CalmDivider()
            }
          }
        }
      }
    }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel("Preview")
        BaonCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Money left this week", style = MaterialTheme.typography.bodyMedium)
            StatusBadge(text = selectedMode.label, tone = BadgeTone.PRIMARY)
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "₱300.00",
            style = MaterialTheme.typography.headlineMedium,
            color = semantic.positive
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Theme updates immediately and saves automatically on your device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsScreen(
  state: BaonPlanUiState,
  hasNotificationPermission: Boolean,
  onSaveSettings: (UserSettingsEntity) -> Unit,
  onRequestNotificationPermission: () -> Unit,
  onOpenSystemNotificationSettings: () -> Unit,
  onSendTestNotification: () -> Unit,
  onRunReminderCheckNow: () -> Unit,
  modifier: Modifier = Modifier
) {
  val settings = state.settings
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      BaonCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Payment & allowance reminders", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "Checks upcoming due dates in Asia/Manila time",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Switch(
            checked = settings.remindersEnabled,
            onCheckedChange = { enabled ->
              onSaveSettings(settings.copy(remindersEnabled = enabled))
            }
          )
        }
        CalmDivider(modifier = Modifier.padding(vertical = 14.dp))
        Text("Weekly allowance check-in day", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
            .forEach { (dayNum, label) ->
              FilterChip(
                selected = settings.allowanceReminderDayOfWeek == dayNum,
                onClick = { onSaveSettings(settings.copy(allowanceReminderDayOfWeek = dayNum)) },
                label = { Text(label) }
              )
            }
        }
      }
    }

    item {
      InfoCallout(
        icon = Icons.Outlined.NotificationsActive,
        title = if (hasNotificationPermission) "Notification permission granted" else "Permission needed on Android 13+",
        body = "Background reminder checks use WorkManager and may be delayed by Android Doze mode or manufacturer battery restrictions.",
        tone = if (hasNotificationPermission) BadgeTone.POSITIVE else BadgeTone.WARNING
      )
    }

    item {
      BaonCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (!hasNotificationPermission) {
            Button(
              onClick = onRequestNotificationPermission,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text("Allow notifications")
            }
          }
          OutlinedButton(
            onClick = onOpenSystemNotificationSettings,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Open Android notification settings")
          }
          OutlinedButton(
            onClick = onSendTestNotification,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Send test alert")
          }
          OutlinedButton(
            onClick = onRunReminderCheckNow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Check due dates now")
          }
        }
      }
    }
  }
}

@Composable
fun BudgetDefaultsScreen(
  settings: UserSettingsEntity,
  onSaveSettings: (UserSettingsEntity) -> Unit,
  modifier: Modifier = Modifier
) {
  var minAllowanceText by remember(settings.minWeeklyAllowanceCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.minWeeklyAllowanceCents))
  }
  var maxAllowanceText by remember(settings.maxWeeklyAllowanceCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.maxWeeklyAllowanceCents))
  }
  var conservativeText by remember(settings.conservativeAllowanceCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.conservativeAllowanceCents))
  }
  var schoolDaysText by remember(settings.schoolDaysPerWeek) {
    mutableStateOf(settings.schoolDaysPerWeek.toString())
  }
  var foodPerDayText by remember(settings.foodPerSchoolDayCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.foodPerSchoolDayCents))
  }
  var otherBufferText by remember(settings.weeklyOtherExpensesBudgetCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.weeklyOtherExpensesBudgetCents))
  }
  var emergencyTargetText by remember(settings.weeklyEmergencySavingsTargetCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.weeklyEmergencySavingsTargetCents))
  }
  var debtReserveTargetText by remember(settings.weeklyDebtReserveTargetCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.weeklyDebtReserveTargetCents))
  }

  val computedFoodBudget = FinancialCalculator.calculateWeeklyFoodBudget(
    schoolDaysPerWeek = schoolDaysText.toIntOrNull() ?: settings.schoolDaysPerWeek,
    foodPerSchoolDayCents = MoneyFormatter.parsePesoToCents(foodPerDayText) ?: settings.foodPerSchoolDayCents
  )

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      BaonCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = minAllowanceText,
              onValueChange = { minAllowanceText = it },
              label = { Text("Min weekly") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = maxAllowanceText,
              onValueChange = { maxAllowanceText = it },
              label = { Text("Max weekly") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
          OutlinedTextField(
            value = conservativeText,
            onValueChange = { conservativeText = it },
            label = { Text("Conservative planning allowance") },
            prefix = { Text("₱") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = schoolDaysText,
              onValueChange = { schoolDaysText = it.filter { ch -> ch.isDigit() } },
              label = { Text("School days / wk") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = foodPerDayText,
              onValueChange = { foodPerDayText = it },
              label = { Text("Food / day") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
          Text(
            text = "Weekly food budget: ${MoneyFormatter.formatPeso(computedFoodBudget)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
          )
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = otherBufferText,
              onValueChange = { otherBufferText = it },
              label = { Text("Other needs") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = emergencyTargetText,
              onValueChange = { emergencyTargetText = it },
              label = { Text("Emergency goal") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
          OutlinedTextField(
            value = debtReserveTargetText,
            onValueChange = { debtReserveTargetText = it },
            label = { Text("Planned weekly debt reserve") },
            prefix = { Text("₱") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          Spacer(modifier = Modifier.height(4.dp))
          Button(
            onClick = {
              val days = (schoolDaysText.toIntOrNull() ?: settings.schoolDaysPerWeek).coerceIn(0, 7)
              val perDay = MoneyFormatter.parsePesoToCents(foodPerDayText) ?: settings.foodPerSchoolDayCents
              val minC = MoneyFormatter.parsePesoToCents(minAllowanceText) ?: settings.minWeeklyAllowanceCents
              val maxC = MoneyFormatter.parsePesoToCents(maxAllowanceText) ?: settings.maxWeeklyAllowanceCents
              val consC = MoneyFormatter.parsePesoToCents(conservativeText) ?: settings.conservativeAllowanceCents
              val otherC = MoneyFormatter.parsePesoToCents(otherBufferText) ?: settings.weeklyOtherExpensesBudgetCents
              val emergC = MoneyFormatter.parsePesoToCents(emergencyTargetText) ?: settings.weeklyEmergencySavingsTargetCents
              val debtResC = MoneyFormatter.parsePesoToCents(debtReserveTargetText) ?: settings.weeklyDebtReserveTargetCents
              onSaveSettings(
                settings.copy(
                  minWeeklyAllowanceCents = minC,
                  maxWeeklyAllowanceCents = maxOf(minC, maxC),
                  conservativeAllowanceCents = consC,
                  schoolDaysPerWeek = days,
                  foodPerSchoolDayCents = perDay,
                  weeklyFoodBudgetCents = FinancialCalculator.calculateWeeklyFoodBudget(days, perDay),
                  weeklyOtherExpensesBudgetCents = otherC,
                  weeklyEmergencySavingsTargetCents = emergC,
                  weeklyDebtReserveTargetCents = debtResC
                )
              )
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Save defaults")
          }
        }
      }
    }
  }
}

@Composable
fun BackupRestoreScreen(
  onExportBackupJson: () -> Unit,
  onImportBackupJson: () -> Unit,
  onExportTransactionsCsv: () -> Unit,
  onClearAllData: () -> Unit,
  modifier: Modifier = Modifier
) {
  var clearStep by remember { mutableIntStateOf(0) }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      BaonCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Back up your local records", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "All data stays on your device. Save a local JSON backup before switching phones or clearing records.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Button(
            onClick = onExportBackupJson,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export backup (JSON)")
          }
          OutlinedButton(
            onClick = onImportBackupJson,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Restore from backup (JSON)")
          }
          OutlinedButton(
            onClick = onExportTransactionsCsv,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export transaction history (CSV)")
          }
        }
      }
    }

    item {
      BaonCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Reset data",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
          )
          Text(
            text = "Removes all debts, installments, allowances, expenses, reserves, and transactions on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          OutlinedButton(
            onClick = { clearStep = 1 },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear all data")
          }
        }
      }
    }
  }

  if (clearStep == 1) {
    AlertDialog(
      onDismissRequest = { clearStep = 0 },
      title = { Text("Clear all data?") },
      text = {
        Text("This deletes all debts, installments, allowances, expenses, reserves, and transactions stored in BaonPlan. Export a JSON backup first if you want a copy.")
      },
      confirmButton = {
        Button(
          onClick = { clearStep = 2 },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Continue")
        }
      },
      dismissButton = {
        TextButton(onClick = { clearStep = 0 }) { Text("Cancel") }
      }
    )
  } else if (clearStep == 2) {
    AlertDialog(
      onDismissRequest = { clearStep = 0 },
      title = { Text("Final confirmation") },
      text = {
        Text("Are you sure you want to erase all local financial records? This cannot be undone without a saved backup file.")
      },
      confirmButton = {
        Button(
          onClick = {
            onClearAllData()
            clearStep = 0
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Delete everything")
        }
      },
      dismissButton = {
        TextButton(onClick = { clearStep = 0 }) { Text("Cancel") }
      }
    )
  }
}

@Composable
fun AboutScreen(
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      BaonCard {
        Text("BaonPlan", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Personal weekly allowance, school budget, and debt repayment tracker designed for Filipino college students.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CalmDivider(modifier = Modifier.padding(vertical = 14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricRow(label = "Application ID", value = "com.baonplan.finance", boldValue = false)
          MetricRow(label = "Currency", value = "PHP (₱)", boldValue = false)
          MetricRow(label = "Timezone", value = "Asia/Manila", boldValue = false)
        }
      }
    }

    item {
      InfoCallout(
        icon = Icons.Outlined.Lock,
        title = "Private & 100% offline",
        body = "BaonPlan never requests internet permission, never connects to any server, disables automatic cloud backup, and never asks for passwords, PINs, or OTPs.",
        tone = BadgeTone.PRIMARY
      )
    }

    item {
      BaonCard {
        Text("Honest due date tracking", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Loans added without lender-confirmed due dates or intervals remain visibly marked as Estimated, Unconfirmed, or Unknown until you confirm them.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}
