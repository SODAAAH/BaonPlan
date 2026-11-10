package com.baonplan.finance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.BudgetAllocationEntity
import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import com.baonplan.finance.domain.budget.BudgetOverviewSummary
import com.baonplan.finance.domain.budget.BudgetRecurrenceEngine
import com.baonplan.finance.domain.budget.FrequencyNormalizationSummary
import com.baonplan.finance.domain.budget.GroupedPaymentItem
import com.baonplan.finance.domain.budget.PaymentDueGroup
import com.baonplan.finance.domain.budget.PaymentGroupingOverview
import com.baonplan.finance.domain.budget.ResolvedIncomeOccurrence
import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.BudgetPeriodStyle
import com.baonplan.finance.domain.model.DebtDeductionMode
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.TwiceMonthlyPattern
import com.baonplan.finance.domain.model.WeeklyCycleMode
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.ui.theme.LocalBaonColors
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SetupBudgetSheet(
  today: LocalDate,
  existingProfile: BudgetProfileEntity,
  onCompleteSetup: (
    source: IncomeSourceEntity,
    profile: BudgetProfileEntity,
    markFirstReceivedToday: Boolean
  ) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val todayIso = remember(today) { ManilaTime.formatIso(today) }

  var sourceName by remember { mutableStateOf("Main Allowance") }
  var amountText by remember { mutableStateOf("") }
  var frequency by remember { mutableStateOf(IncomeFrequency.WEEKLY) }
  var startDateIso by remember { mutableStateOf(todayIso) }
  var customIntervalDaysText by remember { mutableStateOf("10") }
  var customIntervalMonthsText by remember { mutableStateOf("2") }
  var twiceMonthlyPattern by remember { mutableStateOf(TwiceMonthlyPattern.FIRST_AND_FIFTEENTH) }
  var twiceDay1Text by remember { mutableStateOf("5") }
  var twiceDay2Text by remember { mutableStateOf("20") }
  var customDates by remember { mutableStateOf(listOf(todayIso)) }
  var showAddCustomDatePicker by remember { mutableStateOf(false) }

  var weeklyCycleMode by remember { mutableStateOf(existingProfile.weeklyCycleMode) }
  var monthlyCycleMode by remember { mutableStateOf(existingProfile.monthlyCycleMode) }
  var isVariableAmount by remember { mutableStateOf(false) }
  var markFirstReceived by remember { mutableStateOf(false) }
  var rolloverEnabled by remember { mutableStateOf(true) }
  var reserveAheadEnabled by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val startDateParsed = ManilaTime.parseDate(startDateIso)
  val canMarkFirstReceived = startDateParsed != null && !startDateParsed.isAfter(today)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Set up budget", style = MaterialTheme.typography.headlineSmall)
      Text(
        text = "Tell BaonPlan how often you receive your allowance or income. Your loan dues will automatically align to each period.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      OutlinedTextField(
        value = sourceName,
        onValueChange = { sourceName = it },
        label = { Text("Allowance or income name") },
        placeholder = { Text("e.g., Weekly Baon, Salary, Scholarship") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )

      OutlinedTextField(
        value = amountText,
        onValueChange = {
          amountText = it
          errorText = null
        },
        label = { Text("Expected amount per schedule *") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { msg -> { Text(msg) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )

      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "How often do you receive this?",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          IncomeFrequency.entries.forEach { freq ->
            FilterChip(
              selected = frequency == freq,
              onClick = {
                frequency = freq
                errorText = null
              },
              label = { Text(freq.label) }
            )
          }
        }
      }

      FrequencySpecificInputs(
        frequency = frequency,
        customIntervalDaysText = customIntervalDaysText,
        onCustomDaysChange = { customIntervalDaysText = it.filter(Char::isDigit); errorText = null },
        customIntervalMonthsText = customIntervalMonthsText,
        onCustomMonthsChange = { customIntervalMonthsText = it.filter(Char::isDigit); errorText = null },
        twiceMonthlyPattern = twiceMonthlyPattern,
        onTwicePatternChange = { twiceMonthlyPattern = it },
        twiceDay1Text = twiceDay1Text,
        onTwiceDay1Change = { twiceDay1Text = it.filter(Char::isDigit); errorText = null },
        twiceDay2Text = twiceDay2Text,
        onTwiceDay2Change = { twiceDay2Text = it.filter(Char::isDigit); errorText = null },
        customDates = customDates,
        onRemoveCustomDate = { iso -> customDates = customDates.filterNot { it == iso } },
        onRequestAddCustomDate = { showAddCustomDatePicker = true },
        weeklyCycleMode = weeklyCycleMode,
        onWeeklyCycleModeChange = { weeklyCycleMode = it },
        monthlyCycleMode = monthlyCycleMode,
        onMonthlyCycleModeChange = { monthlyCycleMode = it }
      )

      DatePickerField(
        label = if (frequency == IncomeFrequency.ONE_TIME) "Expected date" else "Start / first allowance date",
        dateIso = startDateIso,
        onDateChanged = { picked ->
          if (picked != null) {
            startDateIso = picked
            val parsed = ManilaTime.parseDate(picked)
            if (parsed != null && parsed.isAfter(today)) {
              markFirstReceived = false
            }
          }
        }
      )

      BaonCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = 14.dp
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          SwitchRowItem(
            title = "Variable allowance amount",
            subtitle = "You can adjust the actual cash received each occurrence",
            checked = isVariableAmount,
            onCheckedChange = { isVariableAmount = it }
          )
          if (canMarkFirstReceived) {
            CalmDivider()
            SwitchRowItem(
              title = "Already received on start date",
              subtitle = "Confirm this receipt as actual cash on hand now",
              checked = markFirstReceived,
              onCheckedChange = { markFirstReceived = it }
            )
          }
          CalmDivider()
          SwitchRowItem(
            title = "Roll over unused cash",
            subtitle = "Carry unspent received cash into the next period automatically",
            checked = rolloverEnabled,
            onCheckedChange = { rolloverEnabled = it }
          )
          CalmDivider()
          SwitchRowItem(
            title = "Reserve ahead for upcoming loans",
            subtitle = "Optional: suggest setting aside cash across earlier cycles",
            checked = reserveAheadEnabled,
            onCheckedChange = { reserveAheadEnabled = it }
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Text("Cancel")
        }
        Button(
          onClick = {
            val cents = MoneyFormatter.parsePesoToCents(amountText)
            val customDays = customIntervalDaysText.toIntOrNull()
            val customMonths = customIntervalMonthsText.toIntOrNull()
            val d1 = twiceDay1Text.toIntOrNull()?.coerceIn(1, 31)
            val d2 = twiceDay2Text.toIntOrNull()?.coerceIn(1, 31)

            when {
              cents == null || cents <= 0L -> errorText = "Enter a valid expected peso amount"
              frequency == IncomeFrequency.CUSTOM_DAYS && (customDays == null || customDays <= 0) ->
                errorText = "Enter a valid number of days (at least 1)"
              frequency == IncomeFrequency.CUSTOM_MONTHS && (customMonths == null || customMonths <= 0) ->
                errorText = "Enter a valid number of months (at least 1)"
              frequency == IncomeFrequency.TWICE_MONTHLY &&
                twiceMonthlyPattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS &&
                (d1 == null || d2 == null || d1 == d2) ->
                errorText = "Enter two distinct days of the month (1–31)"
              frequency == IncomeFrequency.CUSTOM_DATES && customDates.isEmpty() ->
                errorText = "Add at least one scheduled date"
              else -> {
                val cleanAnchorDay = ManilaTime.parseDate(startDateIso)?.dayOfMonth ?: today.dayOfMonth
                val source = IncomeSourceEntity(
                  name = sourceName.trim().ifBlank { "Main Allowance" },
                  frequency = frequency,
                  expectedAmountCents = cents,
                  isVariableAmount = isVariableAmount,
                  startDateIso = startDateIso,
                  customIntervalDays = if (frequency == IncomeFrequency.CUSTOM_DAYS) customDays else null,
                  customIntervalMonths = if (frequency == IncomeFrequency.CUSTOM_MONTHS) customMonths else null,
                  twiceMonthlyPattern = twiceMonthlyPattern,
                  twiceMonthlyDay1 = if (frequency == IncomeFrequency.TWICE_MONTHLY && twiceMonthlyPattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS) d1 else null,
                  twiceMonthlyDay2 = if (frequency == IncomeFrequency.TWICE_MONTHLY && twiceMonthlyPattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS) d2 else null,
                  customDatesCsv = if (frequency == IncomeFrequency.CUSTOM_DATES) customDates.sorted().joinToString(",") else "",
                  anchorDayOfMonth = cleanAnchorDay,
                  isActive = true,
                  isPrimary = true
                )
                val profile = existingProfile.copy(
                  id = 1,
                  isConfigured = true,
                  periodStyle = BudgetPeriodStyle.AUTO_FROM_PRIMARY,
                  weeklyCycleMode = weeklyCycleMode,
                  monthlyCycleMode = monthlyCycleMode,
                  rolloverEnabled = rolloverEnabled,
                  reserveAheadEnabled = reserveAheadEnabled
                )
                onCompleteSetup(source, profile, canMarkFirstReceived && markFirstReceived)
                onDismiss()
              }
            }
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1.3f)
        ) {
          Text("Save budget")
        }
      }
    }
  }

  if (showAddCustomDatePicker) {
    BaonDatePickerDialog(
      initialDateIso = customDates.lastOrNull() ?: startDateIso,
      onDateSelected = { picked ->
        if (picked !in customDates) {
          customDates = (customDates + picked).sorted()
          errorText = null
        }
      },
      onDismiss = { showAddCustomDatePicker = false }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun IncomeSourcesManagerSheet(
  today: LocalDate,
  budgetOverview: BudgetOverviewSummary,
  incomeSources: List<IncomeSourceEntity>,
  frequencyNormalization: FrequencyNormalizationSummary,
  onSaveSource: (source: IncomeSourceEntity, effectiveFromDateIso: String?) -> Unit,
  onTogglePauseSource: (sourceId: Long, paused: Boolean, effectiveDateIso: String) -> Unit,
  onEndSource: (sourceId: Long, endDateIso: String) -> Unit,
  onOpenConfirmOccurrence: (ResolvedIncomeOccurrence) -> Unit,
  onDismiss: () -> Unit
) {
  val semantic = LocalBaonColors.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var editingSource by remember { mutableStateOf<IncomeSourceEntity?>(null) }
  var showAddSourceEditor by remember { mutableStateOf(false) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
          Text("Income & receipts", style = MaterialTheme.typography.headlineSmall)
          Text(
            text = "Manage allowance schedules and confirm received cash",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        FilledTonalButton(
          onClick = { showAddSourceEditor = true },
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Add source")
        }
      }

      if (frequencyNormalization.items.isNotEmpty()) {
        BaonCard(
          containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          contentPadding = 14.dp
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = "Normalized monthly baseline",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Comparison only (${frequencyNormalization.items.size} active source${if (frequencyNormalization.items.size == 1) "" else "s"}) · ~${MoneyFormatter.formatPesoCompact(frequencyNormalization.totalNormalizedAnnualCents)}/yr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Text(
              text = "${MoneyFormatter.formatPesoCompact(frequencyNormalization.totalNormalizedMonthlyCents)}/mo",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = semantic.positive
            )
          }
        }
      }

      // Receipts & occurrences in viewed period
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel(
          text = "Occurrences in ${budgetOverview.periodWindow.label}",
          modifier = Modifier.padding(0.dp)
        )
        if (budgetOverview.occurrencesInPeriod.isEmpty()) {
          BaonCard(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentPadding = 14.dp
          ) {
            Text(
              text = "No scheduled allowance occurrences fall inside this period.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        } else {
          budgetOverview.occurrencesInPeriod.forEach { occ ->
            val (badgeText, badgeTone) = when (occ.status) {
              IncomeOccurrenceStatus.EXPECTED -> "Expected" to BadgeTone.WARNING
              IncomeOccurrenceStatus.RECEIVED -> "Received" to BadgeTone.POSITIVE
              IncomeOccurrenceStatus.PARTIALLY_RECEIVED -> "Partial" to BadgeTone.PRIMARY
              IncomeOccurrenceStatus.MISSED -> "Missed" to BadgeTone.DANGER
              IncomeOccurrenceStatus.CANCELLED -> "Cancelled" to BadgeTone.NEUTRAL
            }
            BaonCard(
              onClick = { onOpenConfirmOccurrence(occ) },
              contentPadding = 14.dp
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = occ.sourceName,
                      style = MaterialTheme.typography.titleSmall,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    StatusBadge(text = badgeText, tone = badgeTone)
                  }
                  Spacer(modifier = Modifier.height(3.dp))
                  val dateInfo = if (occ.actualReceivedDate != null && occ.status.isSettledCashIn) {
                    "Scheduled ${ManilaTime.formatDisplay(occ.expectedDate)} · Received ${ManilaTime.formatDisplay(occ.actualReceivedDate)}"
                  } else {
                    "Scheduled ${ManilaTime.formatDisplay(occ.expectedDate)}"
                  }
                  Text(
                    text = dateInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Column(horizontalAlignment = Alignment.End) {
                  val primaryAmount = if (occ.status.isSettledCashIn) {
                    occ.actualReceivedAmountCents
                  } else {
                    occ.expectedAmountCents
                  }
                  Text(
                    text = MoneyFormatter.formatPeso(primaryAmount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (occ.status.isSettledCashIn) semantic.positive else MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = if (occ.status == IncomeOccurrenceStatus.EXPECTED) "Tap to confirm" else "Tap to edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                  )
                }
              }
            }
          }
        }
      }

      // Configured Income Sources
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel(
          text = "Income sources (${incomeSources.size})",
          modifier = Modifier.padding(0.dp)
        )
        if (incomeSources.isEmpty()) {
          BaonCard(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentPadding = 14.dp
          ) {
            Text(
              text = "No income sources added yet.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        } else {
          incomeSources.forEach { src ->
            BaonCard(contentPadding = 14.dp) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                  ) {
                    Text(src.name, style = MaterialTheme.typography.titleMedium)
                    if (src.isPrimary) {
                      StatusBadge(text = "Primary", tone = BadgeTone.PRIMARY)
                    }
                    when {
                      !src.isActive -> StatusBadge(text = "Ended", tone = BadgeTone.NEUTRAL)
                      src.isPaused -> StatusBadge(text = "Paused", tone = BadgeTone.WARNING)
                      else -> StatusBadge(text = "Active", tone = BadgeTone.POSITIVE)
                    }
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = buildString {
                      append(src.frequency.label)
                      if (src.frequency == IncomeFrequency.CUSTOM_DAYS && src.customIntervalDays != null) {
                        append(" (${src.customIntervalDays}d)")
                      } else if (src.frequency == IncomeFrequency.CUSTOM_MONTHS && src.customIntervalMonths != null) {
                        append(" (${src.customIntervalMonths}mo)")
                      }
                      append(" · Start ${ManilaTime.formatDisplay(src.startDateIso)}")
                      if (src.endDateIso != null) {
                        append(" · Ends ${ManilaTime.formatDisplay(src.endDateIso)}")
                      }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Text(
                  text = MoneyFormatter.formatPeso(src.expectedAmountCents),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              }

              Spacer(modifier = Modifier.height(8.dp))
              CalmDivider()
              Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
              ) {
                if (src.isActive) {
                  TextButton(
                    onClick = {
                      onTogglePauseSource(src.id, !src.isPaused, ManilaTime.formatIso(today))
                    }
                  ) {
                    Text(if (src.isPaused) "Resume" else "Pause")
                  }
                  TextButton(
                    onClick = {
                      onEndSource(src.id, ManilaTime.formatIso(today))
                    }
                  ) {
                    Text("End future")
                  }
                }
                TextButton(
                  onClick = { editingSource = src }
                ) {
                  Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Edit")
                }
              }
            }
          }
        }
      }
    }
  }

  if (showAddSourceEditor) {
    IncomeSourceEditorDialog(
      existing = null,
      today = today,
      hasExistingSources = incomeSources.isNotEmpty(),
      onSave = { updated, effectiveIso ->
        onSaveSource(updated, effectiveIso)
        showAddSourceEditor = false
      },
      onDismiss = { showAddSourceEditor = false }
    )
  }

  editingSource?.let { src ->
    IncomeSourceEditorDialog(
      existing = src,
      today = today,
      hasExistingSources = incomeSources.size > 1,
      onSave = { updated, effectiveIso ->
        onSaveSource(updated, effectiveIso)
        editingSource = null
      },
      onDismiss = { editingSource = null }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun IncomeSourceEditorDialog(
  existing: IncomeSourceEntity?,
  today: LocalDate,
  hasExistingSources: Boolean,
  onSave: (source: IncomeSourceEntity, effectiveFromDateIso: String?) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val todayIso = remember(today) { ManilaTime.formatIso(today) }

  var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
  var amountText by remember(existing) {
    mutableStateOf(
      existing?.let { MoneyFormatter.formatEditableAmount(it.expectedAmountCents) } ?: ""
    )
  }
  var frequency by remember(existing) { mutableStateOf(existing?.frequency ?: IncomeFrequency.WEEKLY) }
  var startDateIso by remember(existing) { mutableStateOf(existing?.startDateIso ?: todayIso) }
  var endDateIso by remember(existing) { mutableStateOf(existing?.endDateIso) }
  var effectiveFromIso by remember(existing) { mutableStateOf(todayIso) }
  var customDaysText by remember(existing) { mutableStateOf(existing?.customIntervalDays?.toString() ?: "10") }
  var customMonthsText by remember(existing) { mutableStateOf(existing?.customIntervalMonths?.toString() ?: "2") }
  var twicePattern by remember(existing) {
    mutableStateOf(existing?.twiceMonthlyPattern ?: TwiceMonthlyPattern.FIRST_AND_FIFTEENTH)
  }
  var twiceDay1Text by remember(existing) { mutableStateOf(existing?.twiceMonthlyDay1?.toString() ?: "5") }
  var twiceDay2Text by remember(existing) { mutableStateOf(existing?.twiceMonthlyDay2?.toString() ?: "20") }
  var customDates by remember(existing) {
    mutableStateOf(
      existing?.customDatesCsv?.let { BudgetRecurrenceEngine.parseCustomDates(it).map(ManilaTime::formatIso) }
        ?: listOf(todayIso)
    )
  }
  var showCustomDatePicker by remember { mutableStateOf(false) }
  var isVariable by remember(existing) { mutableStateOf(existing?.isVariableAmount ?: false) }
  var isPrimary by remember(existing) { mutableStateOf(existing?.isPrimary ?: !hasExistingSources) }
  var notes by remember(existing) { mutableStateOf(existing?.notes ?: "") }
  var errorText by remember { mutableStateOf<String?>(null) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text(
        text = if (existing == null) "Add income source" else "Edit ${existing.name}",
        style = MaterialTheme.typography.headlineSmall
      )

      OutlinedTextField(
        value = name,
        onValueChange = { name = it; errorText = null },
        label = { Text("Source name *") },
        placeholder = { Text("e.g., Allowance, Stipend, Freelance") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )

      OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it; errorText = null },
        label = { Text("Expected amount *") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { msg -> { Text(msg) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )

      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "Frequency",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          IncomeFrequency.entries.forEach { freq ->
            FilterChip(
              selected = frequency == freq,
              onClick = { frequency = freq; errorText = null },
              label = { Text(freq.label) }
            )
          }
        }
      }

      FrequencySpecificInputs(
        frequency = frequency,
        customIntervalDaysText = customDaysText,
        onCustomDaysChange = { customDaysText = it.filter(Char::isDigit); errorText = null },
        customIntervalMonthsText = customMonthsText,
        onCustomMonthsChange = { customMonthsText = it.filter(Char::isDigit); errorText = null },
        twiceMonthlyPattern = twicePattern,
        onTwicePatternChange = { twicePattern = it },
        twiceDay1Text = twiceDay1Text,
        onTwiceDay1Change = { twiceDay1Text = it.filter(Char::isDigit); errorText = null },
        twiceDay2Text = twiceDay2Text,
        onTwiceDay2Change = { twiceDay2Text = it.filter(Char::isDigit); errorText = null },
        customDates = customDates,
        onRemoveCustomDate = { iso -> customDates = customDates.filterNot { it == iso } },
        onRequestAddCustomDate = { showCustomDatePicker = true },
        weeklyCycleMode = null,
        onWeeklyCycleModeChange = null,
        monthlyCycleMode = null,
        onMonthlyCycleModeChange = null
      )

      DatePickerField(
        label = "Start date",
        dateIso = startDateIso,
        onDateChanged = { if (it != null) startDateIso = it }
      )

      if (frequency != IncomeFrequency.ONE_TIME) {
        DatePickerField(
          label = "Optional end date",
          dateIso = endDateIso,
          onDateChanged = { endDateIso = it },
          allowClear = true
        )
      }

      if (existing != null) {
        DatePickerField(
          label = "Apply schedule change from",
          dateIso = effectiveFromIso,
          onDateChanged = { if (it != null) effectiveFromIso = it }
        )
        Text(
          text = "Occurrences before this date stay preserved so earlier budget history will not rewrite.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      SwitchRowItem(
        title = "Variable expected amount",
        subtitle = "Actual received cash can vary per occurrence",
        checked = isVariable,
        onCheckedChange = { isVariable = it }
      )

      SwitchRowItem(
        title = "Primary allowance schedule",
        subtitle = "Drives the default budget period length when Auto is selected",
        checked = isPrimary,
        onCheckedChange = { isPrimary = it }
      )

      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Notes (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Text("Cancel")
        }
        Button(
          onClick = {
            val cleanName = name.trim()
            val cents = MoneyFormatter.parsePesoToCents(amountText)
            val cDays = customDaysText.toIntOrNull()
            val cMonths = customMonthsText.toIntOrNull()
            val d1 = twiceDay1Text.toIntOrNull()?.coerceIn(1, 31)
            val d2 = twiceDay2Text.toIntOrNull()?.coerceIn(1, 31)

            when {
              cleanName.isEmpty() -> errorText = "Enter an income source name"
              cents == null || cents <= 0L -> errorText = "Enter a valid peso amount"
              frequency == IncomeFrequency.CUSTOM_DAYS && (cDays == null || cDays <= 0) ->
                errorText = "Enter a valid interval in days"
              frequency == IncomeFrequency.CUSTOM_MONTHS && (cMonths == null || cMonths <= 0) ->
                errorText = "Enter a valid interval in months"
              frequency == IncomeFrequency.TWICE_MONTHLY &&
                twicePattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS &&
                (d1 == null || d2 == null || d1 == d2) ->
                errorText = "Pick two distinct day numbers (1–31)"
              frequency == IncomeFrequency.CUSTOM_DATES && customDates.isEmpty() ->
                errorText = "Add at least one custom date"
              else -> {
                val anchorDay = ManilaTime.parseDate(startDateIso)?.dayOfMonth ?: today.dayOfMonth
                val entity = (existing ?: IncomeSourceEntity(name = cleanName, expectedAmountCents = cents, startDateIso = startDateIso)).copy(
                  name = cleanName,
                  frequency = frequency,
                  expectedAmountCents = cents,
                  isVariableAmount = isVariable,
                  startDateIso = startDateIso,
                  endDateIso = if (frequency == IncomeFrequency.ONE_TIME) null else endDateIso,
                  customIntervalDays = if (frequency == IncomeFrequency.CUSTOM_DAYS) cDays else null,
                  customIntervalMonths = if (frequency == IncomeFrequency.CUSTOM_MONTHS) cMonths else null,
                  twiceMonthlyPattern = twicePattern,
                  twiceMonthlyDay1 = if (frequency == IncomeFrequency.TWICE_MONTHLY && twicePattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS) d1 else null,
                  twiceMonthlyDay2 = if (frequency == IncomeFrequency.TWICE_MONTHLY && twicePattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS) d2 else null,
                  customDatesCsv = if (frequency == IncomeFrequency.CUSTOM_DATES) customDates.sorted().joinToString(",") else "",
                  anchorDayOfMonth = anchorDay,
                  isActive = true,
                  isPrimary = isPrimary,
                  notes = notes.trim()
                )
                onSave(entity, if (existing != null) effectiveFromIso else null)
              }
            }
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1.3f)
        ) {
          Text("Save source")
        }
      }
    }
  }

  if (showCustomDatePicker) {
    BaonDatePickerDialog(
      initialDateIso = customDates.lastOrNull() ?: startDateIso,
      onDateSelected = { picked ->
        if (picked !in customDates) {
          customDates = (customDates + picked).sorted()
          errorText = null
        }
      },
      onDismiss = { showCustomDatePicker = false }
    )
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConfirmIncomeOccurrenceDialog(
  occurrence: ResolvedIncomeOccurrence,
  today: LocalDate,
  onConfirm: (
    status: IncomeOccurrenceStatus,
    actualAmountCents: Long,
    actualDateIso: String?,
    notes: String
  ) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val initialStatus = remember(occurrence) {
    if (occurrence.status == IncomeOccurrenceStatus.EXPECTED) {
      IncomeOccurrenceStatus.RECEIVED
    } else {
      occurrence.status
    }
  }
  var status by remember(occurrence) { mutableStateOf(initialStatus) }
  var actualAmountText by remember(occurrence) {
    val defaultAmt = when {
      occurrence.actualReceivedAmountCents > 0L -> occurrence.actualReceivedAmountCents
      else -> occurrence.expectedAmountCents
    }
    mutableStateOf(MoneyFormatter.formatEditableAmount(defaultAmt))
  }
  var actualDateIso by remember(occurrence) {
    mutableStateOf(
      occurrence.actualReceivedDate?.let(ManilaTime::formatIso)
        ?: ManilaTime.formatIso(
          if (occurrence.expectedDate.isAfter(today)) today else occurrence.expectedDate
        )
    )
  }
  var notes by remember(occurrence) { mutableStateOf(occurrence.notes) }
  var errorText by remember { mutableStateOf<String?>(null) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Allowance receipt", style = MaterialTheme.typography.headlineSmall)
      BaonCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = 14.dp
      ) {
        MetricRow(label = occurrence.sourceName, value = MoneyFormatter.formatPeso(occurrence.expectedAmountCents))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Scheduled for ${ManilaTime.formatFullDisplay(ManilaTime.formatIso(occurrence.expectedDate))}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        listOf(
          IncomeOccurrenceStatus.RECEIVED to "Received",
          IncomeOccurrenceStatus.PARTIALLY_RECEIVED to "Partial",
          IncomeOccurrenceStatus.MISSED to "Missed",
          IncomeOccurrenceStatus.CANCELLED to "Cancelled",
          IncomeOccurrenceStatus.EXPECTED to "Reset to expected"
        ).forEach { (st, lbl) ->
          FilterChip(
            selected = status == st,
            onClick = {
              status = st
              errorText = null
            },
            label = { Text(lbl) }
          )
        }
      }

      if (status.isSettledCashIn) {
        OutlinedTextField(
          value = actualAmountText,
          onValueChange = { actualAmountText = it; errorText = null },
          label = { Text("Actual cash received *") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          isError = errorText != null,
          supportingText = errorText?.let { msg -> { Text(msg) } },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        )
        DatePickerField(
          label = "Actual date received",
          dateIso = actualDateIso,
          onDateChanged = { if (it != null) actualDateIso = it }
        )
      } else {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Outlined.Info,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp)
            )
            Text(
              text = when (status) {
                IncomeOccurrenceStatus.MISSED -> "No cash record will be created. Actual available cash reflects only confirmed receipts."
                IncomeOccurrenceStatus.CANCELLED -> "This scheduled occurrence will be excluded from expected and actual income."
                else -> "Marks this occurrence back as expected without an actual cash receipt."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Note (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        ) {
          Text("Cancel")
        }
        Button(
          onClick = {
            if (status.isSettledCashIn) {
              val cents = MoneyFormatter.parsePesoToCents(actualAmountText)
              if (cents == null || cents <= 0L) {
                errorText = "Enter a valid peso amount"
              } else {
                onConfirm(status, cents, actualDateIso, notes.trim())
                onDismiss()
              }
            } else {
              onConfirm(status, 0L, null, notes.trim())
              onDismiss()
            }
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1.3f)
        ) {
          Text("Save")
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BudgetSettingsAndAllocationsSheet(
  profile: BudgetProfileEntity,
  allocations: List<BudgetAllocationEntity>,
  onSaveProfile: (BudgetProfileEntity) -> Unit,
  onUpsertAllocation: (categoryKey: BudgetCategoryKey, amountCents: Long, enabled: Boolean) -> Unit,
  onDeleteAllocation: (categoryKey: BudgetCategoryKey) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var periodStyle by remember(profile) { mutableStateOf(profile.periodStyle) }
  var customDaysText by remember(profile) { mutableStateOf(profile.customPeriodDays?.toString() ?: "14") }
  var weeklyMode by remember(profile) { mutableStateOf(profile.weeklyCycleMode) }
  var monthlyMode by remember(profile) { mutableStateOf(profile.monthlyCycleMode) }
  var rolloverEnabled by remember(profile) { mutableStateOf(profile.rolloverEnabled) }
  var reserveAheadEnabled by remember(profile) { mutableStateOf(profile.reserveAheadEnabled) }
  var includeExpected by remember(profile) { mutableStateOf(profile.includeExpectedIncomeInProjection) }
  var includeOverdue by remember(profile) { mutableStateOf(profile.includeOverdueInBudget) }
  var deductionMode by remember(profile) { mutableStateOf(profile.debtDeductionMode) }
  var settingsError by remember { mutableStateOf<String?>(null) }

  val allocationMap = remember(allocations) { allocations.associateBy { it.categoryKey } }
  val allocationInputs = remember(allocations) {
    mutableStateMapOf<BudgetCategoryKey, String>().apply {
      BudgetCategoryKey.entries.forEach { key ->
        val existing = allocationMap[key]
        put(
          key,
          if (existing != null && existing.allocatedAmountCents > 0L) {
            MoneyFormatter.formatEditableAmount(existing.allocatedAmountCents)
          } else ""
        )
      }
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Text("Budget settings & categories", style = MaterialTheme.typography.headlineSmall)

      CalmSectionLabel("Budget cycle", modifier = Modifier.padding(0.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        BudgetPeriodStyle.entries.forEach { style ->
          FilterChip(
            selected = periodStyle == style,
            onClick = { periodStyle = style; settingsError = null },
            label = { Text(style.label) }
          )
        }
      }

      if (periodStyle == BudgetPeriodStyle.CUSTOM_DAYS) {
        OutlinedTextField(
          value = customDaysText,
          onValueChange = { customDaysText = it.filter(Char::isDigit); settingsError = null },
          label = { Text("Budget cycle length (days)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = settingsError != null,
          supportingText = settingsError?.let { msg -> { Text(msg) } },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        )
      }

      if (periodStyle == BudgetPeriodStyle.AUTO_FROM_PRIMARY || periodStyle == BudgetPeriodStyle.WEEKLY) {
        CalmSegmentedToggle(
          options = listOf(
            WeeklyCycleMode.ANCHOR_ALIGNED to "From allowance date",
            WeeklyCycleMode.MONDAY_TO_SUNDAY to "Mon–Sun calendar"
          ),
          selected = weeklyMode,
          onSelect = { weeklyMode = it }
        )
      }

      if (periodStyle == BudgetPeriodStyle.AUTO_FROM_PRIMARY || periodStyle == BudgetPeriodStyle.MONTHLY) {
        CalmSegmentedToggle(
          options = listOf(
            MonthlyCycleMode.CALENDAR_MONTH to "Calendar month",
            MonthlyCycleMode.PAYDAY_CYCLE to "Payday to payday"
          ),
          selected = monthlyMode,
          onSelect = { monthlyMode = it }
        )
      }

      BaonCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = 14.dp
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          SwitchRowItem(
            title = "Roll over unused actual cash",
            subtitle = "Automatically carry over confirmed net leftover from prior periods",
            checked = rolloverEnabled,
            onCheckedChange = { rolloverEnabled = it }
          )
          CalmDivider()
          SwitchRowItem(
            title = "Reserve ahead for upcoming loans",
            subtitle = "Split upcoming installments across cycles before their due date",
            checked = reserveAheadEnabled,
            onCheckedChange = { reserveAheadEnabled = it }
          )
          CalmDivider()
          SwitchRowItem(
            title = "Include expected income in forecast",
            subtitle = "Actual cash remaining always counts only confirmed receipts",
            checked = includeExpected,
            onCheckedChange = { includeExpected = it }
          )
          CalmDivider()
          SwitchRowItem(
            title = "Include overdue loans in spendable left",
            subtitle = "Overdue loans always stay visible even if excluded from period spending math",
            checked = includeOverdue,
            onCheckedChange = { includeOverdue = it }
          )
          CalmDivider()
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Debt obligation deduction", style = MaterialTheme.typography.titleSmall)
            CalmSegmentedToggle(
              options = listOf(
                DebtDeductionMode.UNPAID_ONLY to "Unpaid remaining",
                DebtDeductionMode.FULL_DUE to "Full installment due"
              ),
              selected = deductionMode,
              onSelect = { deductionMode = it }
            )
          }
        }
      }

      Button(
        onClick = {
          val customDays = customDaysText.toIntOrNull()
          if (periodStyle == BudgetPeriodStyle.CUSTOM_DAYS && (customDays == null || customDays <= 0)) {
            settingsError = "Enter at least 1 day for custom budget period"
          } else {
            onSaveProfile(
              profile.copy(
                id = 1,
                isConfigured = true,
                periodStyle = periodStyle,
                customPeriodDays = if (periodStyle == BudgetPeriodStyle.CUSTOM_DAYS) customDays else profile.customPeriodDays,
                weeklyCycleMode = weeklyMode,
                monthlyCycleMode = monthlyMode,
                rolloverEnabled = rolloverEnabled,
                reserveAheadEnabled = reserveAheadEnabled,
                includeExpectedIncomeInProjection = includeExpected,
                includeOverdueInBudget = includeOverdue,
                debtDeductionMode = deductionMode
              )
            )
          }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text("Save budget preferences")
      }

      CalmDivider()

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmSectionLabel("Optional category & savings plans", modifier = Modifier.padding(0.dp))
        Text(
          text = "Only categories you configure affect your planned remaining budget. Leave blank for zero deduction.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BudgetCategoryKey.entries.forEach { key ->
          val existing = allocationMap[key]
          val rawInput = allocationInputs[key] ?: ""
          val parsed = MoneyFormatter.parsePesoToCents(rawInput)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedTextField(
              value = rawInput,
              onValueChange = { allocationInputs[key] = it },
              label = { Text(key.label) },
              prefix = { Text("₱") },
              placeholder = { Text("Optional") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
              onClick = {
                val cents = parsed
                if (cents != null && cents > 0L) {
                  onUpsertAllocation(key, cents, true)
                } else if (existing != null) {
                  onDeleteAllocation(key)
                  allocationInputs[key] = ""
                }
              },
              enabled = (parsed != null && parsed > 0L) || (rawInput.isBlank() && existing != null),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
              Text(if (existing == null) "Set" else "Update")
            }
            if (existing != null) {
              IconButton(
                onClick = {
                  onDeleteAllocation(key)
                  allocationInputs[key] = ""
                }
              ) {
                Icon(
                  imageVector = Icons.Outlined.DeleteOutline,
                  contentDescription = "Remove ${key.label} allocation",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentGroupBreakdownSheet(
  grouping: PaymentGroupingOverview,
  focusedGroup: PaymentDueGroup?,
  onOpenDebtDetail: (Long) -> Unit,
  onRecordPaymentForDebt: (Long) -> Unit,
  onDismiss: () -> Unit
) {
  val semantic = LocalBaonColors.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val upcomingPrimaryGroup = if (focusedGroup != null && !focusedGroup.isOverdueGroup) {
    focusedGroup
  } else {
    grouping.nextGroup
  }
  val otherUpcomingGroups = if (upcomingPrimaryGroup != null) {
    (listOfNotNull(grouping.nextGroup) + grouping.followingGroups)
      .filterNot { it.startDate == upcomingPrimaryGroup.startDate && it.endDate == upcomingPrimaryGroup.endDate }
  } else {
    grouping.followingGroups
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Upcoming payment breakdown", style = MaterialTheme.typography.headlineSmall)
        IconButton(onClick = onDismiss) {
          Icon(Icons.Outlined.Close, contentDescription = "Close")
        }
      }

      grouping.overdueGroup?.let { overdue ->
        GroupBreakdownSection(
          title = "Overdue obligations",
          subtitle = overdue.countdownLabel,
          totalSummary = buildGroupTotalLabel(overdue, prefix = "Total overdue"),
          group = overdue,
          headerTone = BadgeTone.DANGER,
          onOpenDebtDetail = onOpenDebtDetail,
          onRecordPaymentForDebt = onRecordPaymentForDebt
        )
      }

      if (upcomingPrimaryGroup != null) {
        GroupBreakdownSection(
          title = upcomingPrimaryGroup.headlineLabel,
          subtitle = upcomingPrimaryGroup.countdownLabel,
          totalSummary = buildGroupTotalLabel(upcomingPrimaryGroup),
          group = upcomingPrimaryGroup,
          headerTone = BadgeTone.PRIMARY,
          onOpenDebtDetail = onOpenDebtDetail,
          onRecordPaymentForDebt = onRecordPaymentForDebt
        )
      }

      if (otherUpcomingGroups.isNotEmpty()) {
        CalmSectionLabel("Following groups", modifier = Modifier.padding(0.dp))
        otherUpcomingGroups.forEach { grp ->
          BaonCard(
            onClick = {
              onDismiss()
              onOpenDebtDetail(grp.primaryDebtId)
            },
            contentPadding = 14.dp
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = grp.headlineLabel,
                  style = MaterialTheme.typography.titleSmall
                )
                Text(
                  text = "${grp.totalPaymentsCount} ${if (grp.totalPaymentsCount == 1) "payment" else "payments"} · ${grp.distinctLoansCount} ${if (grp.distinctLoansCount == 1) "loan" else "loans"} · ${grp.countdownLabel}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              Text(
                text = if (grp.knownTotalCents > 0L) {
                  MoneyFormatter.formatPeso(grp.knownTotalCents) + if (grp.hasIncompleteAmounts) "+" else ""
                } else {
                  "Amount unavailable"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (grp.hasIncompleteAmounts && grp.knownTotalCents == 0L) semantic.warning else MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      }

      if (grouping.undatedOrUnconfirmedPayments.isNotEmpty()) {
        CalmSectionLabel(
          text = "Unconfirmed due dates (${grouping.undatedOrUnconfirmedPayments.size})",
          modifier = Modifier.padding(0.dp)
        )
        grouping.undatedOrUnconfirmedPayments.forEach { item ->
          PaymentItemBreakdownRow(
            item = item,
            onOpenDebtDetail = {
              onDismiss()
              onOpenDebtDetail(item.debtId)
            },
            onRecordPaymentForDebt = {
              onDismiss()
              onRecordPaymentForDebt(item.debtId)
            }
          )
        }
      }
    }
  }
}

@Composable
private fun GroupBreakdownSection(
  title: String,
  subtitle: String,
  totalSummary: String,
  group: PaymentDueGroup,
  headerTone: BadgeTone,
  onOpenDebtDetail: (Long) -> Unit,
  onRecordPaymentForDebt: (Long) -> Unit
) {
  val semantic = LocalBaonColors.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(title, style = MaterialTheme.typography.titleMedium)
          StatusBadge(text = subtitle, tone = headerTone)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = totalSummary,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = if (group.isOverdueGroup) semantic.danger else MaterialTheme.colorScheme.onSurface
        )
      }
      Text(
        text = "${group.totalPaymentsCount}p · ${group.distinctLoansCount}L",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    if (group.hasIncompleteAmounts) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Outlined.WarningAmber,
          contentDescription = null,
          tint = semantic.warning,
          modifier = Modifier.size(16.dp)
        )
        Text(
          text = "Group total is incomplete — at least one payment has an unknown amount.",
          style = MaterialTheme.typography.bodySmall,
          color = semantic.warning
        )
      }
    }

    group.items.forEach { item ->
      PaymentItemBreakdownRow(
        item = item,
        onOpenDebtDetail = { onOpenDebtDetail(item.debtId) },
        onRecordPaymentForDebt = { onRecordPaymentForDebt(item.debtId) }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentItemBreakdownRow(
  item: GroupedPaymentItem,
  onOpenDebtDetail: () -> Unit,
  onRecordPaymentForDebt: () -> Unit
) {
  val semantic = LocalBaonColors.current
  BaonCard(
    onClick = onOpenDebtDetail,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding = 14.dp
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
        Text(
          text = item.debtName,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        val exactDateStr = if (item.dueDateIso != null) {
          ManilaTime.formatFullDisplay(item.dueDateIso)
        } else {
          "Due date not confirmed"
        }
        Text(
          text = "${item.providerName} · $exactDateStr",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          when {
            item.isOverdue -> StatusBadge("Overdue", BadgeTone.DANGER)
            item.isPending -> StatusBadge("Pending verification", BadgeTone.WARNING)
            item.installmentStatus == InstallmentStatus.PARTIAL -> StatusBadge("Partial", BadgeTone.PRIMARY)
            else -> StatusBadge("Unpaid", BadgeTone.NEUTRAL)
          }
          if (item.isEstimatedDate) {
            StatusBadge("Estimated date", BadgeTone.WARNING)
          }
          if (item.dueDate == null) {
            StatusBadge("Confirm date", BadgeTone.WARNING)
          }
        }
      }
      Column(horizontalAlignment = Alignment.End) {
        val amountLabel = item.unpaidAmountCents?.let { MoneyFormatter.formatPeso(it) } ?: "Amount unavailable"
        Text(
          text = amountLabel,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = if (item.unpaidAmountCents == null) semantic.warning else MaterialTheme.colorScheme.onSurface
        )
        if (item.paidAmountCents > 0L && item.totalDueCents != null) {
          Text(
            text = "${MoneyFormatter.formatPesoCompact(item.paidAmountCents)} paid of ${MoneyFormatter.formatPesoCompact(item.totalDueCents)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(modifier = Modifier.height(2.dp))
        TextButton(
          onClick = onRecordPaymentForDebt,
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
        ) {
          Text("Pay", style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}

private fun buildGroupTotalLabel(group: PaymentDueGroup, prefix: String? = null): String {
  val head = prefix ?: "Total due ${group.dateRangeLabel}"
  return if (group.knownTotalCents > 0L) {
    val suffix = if (group.hasIncompleteAmounts) " + unknown amount" else ""
    "$head: ${MoneyFormatter.formatPeso(group.knownTotalCents)}$suffix"
  } else {
    "$head: Amount unavailable"
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrequencySpecificInputs(
  frequency: IncomeFrequency,
  customIntervalDaysText: String,
  onCustomDaysChange: (String) -> Unit,
  customIntervalMonthsText: String,
  onCustomMonthsChange: (String) -> Unit,
  twiceMonthlyPattern: TwiceMonthlyPattern,
  onTwicePatternChange: (TwiceMonthlyPattern) -> Unit,
  twiceDay1Text: String,
  onTwiceDay1Change: (String) -> Unit,
  twiceDay2Text: String,
  onTwiceDay2Change: (String) -> Unit,
  customDates: List<String>,
  onRemoveCustomDate: (String) -> Unit,
  onRequestAddCustomDate: () -> Unit,
  weeklyCycleMode: WeeklyCycleMode?,
  onWeeklyCycleModeChange: ((WeeklyCycleMode) -> Unit)?,
  monthlyCycleMode: MonthlyCycleMode?,
  onMonthlyCycleModeChange: ((MonthlyCycleMode) -> Unit)?
) {
  when (frequency) {
    IncomeFrequency.WEEKLY -> {
      if (weeklyCycleMode != null && onWeeklyCycleModeChange != null) {
        CalmSegmentedToggle(
          options = listOf(
            WeeklyCycleMode.ANCHOR_ALIGNED to "From allowance date",
            WeeklyCycleMode.MONDAY_TO_SUNDAY to "Mon–Sun calendar"
          ),
          selected = weeklyCycleMode,
          onSelect = onWeeklyCycleModeChange
        )
      }
    }
    IncomeFrequency.TWICE_MONTHLY -> {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Twice-monthly pay dates",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          TwiceMonthlyPattern.entries.forEach { pat ->
            FilterChip(
              selected = twiceMonthlyPattern == pat,
              onClick = { onTwicePatternChange(pat) },
              label = { Text(pat.label) }
            )
          }
        }
        if (twiceMonthlyPattern == TwiceMonthlyPattern.CUSTOM_TWO_DAYS) {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = twiceDay1Text,
              onValueChange = onTwiceDay1Change,
              label = { Text("First day (1–31)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = twiceDay2Text,
              onValueChange = onTwiceDay2Change,
              label = { Text("Second day (1–31)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }
    IncomeFrequency.MONTHLY -> {
      if (monthlyCycleMode != null && onMonthlyCycleModeChange != null) {
        CalmSegmentedToggle(
          options = listOf(
            MonthlyCycleMode.CALENDAR_MONTH to "Calendar month",
            MonthlyCycleMode.PAYDAY_CYCLE to "Payday to payday"
          ),
          selected = monthlyCycleMode,
          onSelect = onMonthlyCycleModeChange
        )
      }
    }
    IncomeFrequency.CUSTOM_DAYS -> {
      OutlinedTextField(
        value = customIntervalDaysText,
        onValueChange = onCustomDaysChange,
        label = { Text("Every how many days?") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
    }
    IncomeFrequency.CUSTOM_MONTHS -> {
      OutlinedTextField(
        value = customIntervalMonthsText,
        onValueChange = onCustomMonthsChange,
        label = { Text("Every how many months?") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
    }
    IncomeFrequency.CUSTOM_DATES -> {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Scheduled dates (${customDates.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          TextButton(onClick = onRequestAddCustomDate) {
            Text("+ Add date")
          }
        }
        if (customDates.isNotEmpty()) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            customDates.forEach { iso ->
              InputChip(
                selected = false,
                onClick = { onRemoveCustomDate(iso) },
                label = { Text(ManilaTime.formatDisplay(iso)) },
                trailingIcon = {
                  Icon(Icons.Outlined.Close, contentDescription = "Remove date", modifier = Modifier.size(14.dp))
                }
              )
            }
          }
        }
      }
    }
    else -> Unit
  }
}

@Composable
private fun SwitchRowItem(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
