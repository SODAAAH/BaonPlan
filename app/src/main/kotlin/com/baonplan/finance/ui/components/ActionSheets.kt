package com.baonplan.finance.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaonDatePickerDialog(
  initialDateIso: String?,
  onDateSelected: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val initialLocalDate = ManilaTime.parseDate(initialDateIso) ?: ManilaTime.today()
  val initialMillis = initialLocalDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
  val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(
        onClick = {
          val millis = pickerState.selectedDateMillis
          if (millis != null) {
            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            onDateSelected(ManilaTime.formatIso(picked))
          }
          onDismiss()
        }
      ) {
        Text("Select")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  ) {
    DatePicker(state = pickerState)
  }
}

@Composable
fun DatePickerField(
  label: String,
  dateIso: String?,
  onDateChanged: (String?) -> Unit,
  allowClear: Boolean = false
) {
  var showPicker by remember { mutableStateOf(false) }

  OutlinedTextField(
    value = dateIso?.let { ManilaTime.formatDisplay(it) } ?: "Not set",
    onValueChange = {},
    readOnly = true,
    label = { Text(label) },
    trailingIcon = {
      Row {
        if (allowClear && dateIso != null) {
          IconButton(onClick = { onDateChanged(null) }) {
            Icon(Icons.Outlined.Clear, contentDescription = "Clear date")
          }
        }
        IconButton(onClick = { showPicker = true }) {
          Icon(Icons.Outlined.CalendarToday, contentDescription = "Pick date")
        }
      }
    },
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth()
  )

  if (showPicker) {
    BaonDatePickerDialog(
      initialDateIso = dateIso,
      onDateSelected = { onDateChanged(it) },
      onDismiss = { showPicker = false }
    )
  }
}

@Composable
private fun SheetButtonRow(
  cancelText: String = "Cancel",
  confirmText: String,
  onCancel: () -> Unit,
  onConfirm: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    OutlinedButton(
      onClick = onCancel,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.weight(1f)
    ) {
      Text(cancelText)
    }
    Button(
      onClick = onConfirm,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.weight(1.3f)
    ) {
      Text(confirmText)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecordAllowanceDialog(
  defaultAmountCents: Long,
  minPresetCents: Long = 40_000L,
  maxPresetCents: Long = 50_000L,
  today: LocalDate,
  onConfirm: (amountCents: Long, dateIso: String, source: String, notes: String) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var amountText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(defaultAmountCents)) }
  var dateIso by remember { mutableStateOf(ManilaTime.formatIso(today)) }
  var notes by remember { mutableStateOf("") }
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Record allowance", style = MaterialTheme.typography.headlineSmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(minPresetCents, maxPresetCents).distinct().forEach { cents ->
          FilterChip(
            selected = MoneyFormatter.parsePesoToCents(amountText) == cents,
            onClick = {
              amountText = MoneyFormatter.formatEditableAmount(cents)
              errorText = null
            },
            label = { Text(MoneyFormatter.formatPesoCompact(cents)) }
          )
        }
      }
      OutlinedTextField(
        value = amountText,
        onValueChange = {
          amountText = it
          errorText = null
        },
        label = { Text("Amount") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { msg -> { Text(msg) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      DatePickerField(
        label = "Date received",
        dateIso = dateIso,
        onDateChanged = { if (it != null) dateIso = it }
      )
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Note (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      SheetButtonRow(
        confirmText = "Save allowance",
        onCancel = onDismiss,
        onConfirm = {
          val cents = MoneyFormatter.parsePesoToCents(amountText)
          if (cents == null || cents <= 0L) {
            errorText = "Enter a valid peso amount"
          } else {
            onConfirm(cents, dateIso, "Weekly Allowance", notes.trim())
            onDismiss()
          }
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseDialog(
  defaultFoodCents: Long,
  presets: List<ExpensePreset> = emptyList(),
  today: LocalDate,
  onConfirm: (
    title: String,
    amountCents: Long,
    dateIso: String,
    category: ExpenseCategory,
    isSchoolDay: Boolean,
    notes: String
  ) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var category by remember { mutableStateOf(ExpenseCategory.SCHOOL_FOOD) }
  var isSchoolDay by remember { mutableStateOf(true) }
  var amountText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(defaultFoodCents)) }
  var dateIso by remember { mutableStateOf(ManilaTime.formatIso(today)) }
  var notes by remember { mutableStateOf("") }
  var errorText by remember { mutableStateOf<String?>(null) }

  val categoryChipLabels = mapOf(
    ExpenseCategory.SCHOOL_FOOD to "Food",
    ExpenseCategory.TRANSPORT to "Transport",
    ExpenseCategory.SCHOOL_SUPPLIES to "Supplies",
    ExpenseCategory.PERSONAL to "Personal",
    ExpenseCategory.EMERGENCY to "Emergency",
    ExpenseCategory.OTHER to "Other"
  )

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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Add expense", style = MaterialTheme.typography.headlineSmall)
      if (presets.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          presets.forEach { preset ->
            FilterChip(
              selected = category == preset.category &&
                MoneyFormatter.parsePesoToCents(amountText) == preset.amountCents &&
                notes.trim() == preset.label,
              onClick = {
                category = preset.category
                amountText = MoneyFormatter.formatEditableAmount(preset.amountCents)
                isSchoolDay = preset.isSchoolDay
                notes = preset.label
                errorText = null
              },
              label = { Text("${preset.label} · ${MoneyFormatter.formatPesoCompact(preset.amountCents)}") }
            )
          }
        }
      }
      OutlinedTextField(
        value = amountText,
        onValueChange = {
          amountText = it
          errorText = null
        },
        label = { Text("Amount") },
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
          text = "Category",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          ExpenseCategory.entries.forEach { cat ->
            FilterChip(
              selected = category == cat,
              onClick = {
                category = cat
                isSchoolDay = (cat == ExpenseCategory.SCHOOL_FOOD || cat == ExpenseCategory.TRANSPORT)
                if (cat == ExpenseCategory.SCHOOL_FOOD && amountText.isBlank()) {
                  amountText = MoneyFormatter.formatEditableAmount(defaultFoodCents)
                }
              },
              label = { Text(categoryChipLabels[cat] ?: cat.label) }
            )
          }
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "Count toward school days",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = isSchoolDay, onCheckedChange = { isSchoolDay = it })
      }
      DatePickerField(
        label = "Date",
        dateIso = dateIso,
        onDateChanged = { if (it != null) dateIso = it }
      )
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Note (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      SheetButtonRow(
        confirmText = "Save expense",
        onCancel = onDismiss,
        onConfirm = {
          val cents = MoneyFormatter.parsePesoToCents(amountText)
          if (cents == null || cents <= 0L) {
            errorText = "Enter a valid peso amount"
          } else {
            val cleanNote = notes.trim()
            val resolvedTitle = cleanNote.ifBlank {
              if (category == ExpenseCategory.SCHOOL_FOOD) "School food" else category.label
            }
            onConfirm(
              resolvedTitle,
              cents,
              dateIso,
              category,
              isSchoolDay,
              cleanNote
            )
            onDismiss()
          }
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecordRepaymentDialog(
  activeDebts: List<DebtEntity>,
  initialDebtId: Long?,
  availableDebtReserveCents: Long,
  today: LocalDate,
  onConfirm: (
    debtId: Long,
    amountCents: Long,
    dateIso: String,
    useDebtReserve: Boolean,
    notes: String
  ) -> Unit,
  onDismiss: () -> Unit,
  onAddDebt: (() -> Unit)? = null
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var selectedDebtId by remember {
    mutableLongStateOf(initialDebtId ?: activeDebts.firstOrNull()?.id ?: 0L)
  }
  val selectedDebt = activeDebts.firstOrNull { it.id == selectedDebtId }
  val suggestedInstallment = selectedDebt?.installmentAmountCents?.takeIf { it > 0L }
    ?: selectedDebt?.remainingBalanceCents ?: 0L

  var amountText by remember(selectedDebtId) {
    mutableStateOf(
      if (suggestedInstallment > 0L) MoneyFormatter.formatEditableAmount(suggestedInstallment) else ""
    )
  }
  var dateIso by remember { mutableStateOf(ManilaTime.formatIso(today)) }
  var useDebtReserve by remember { mutableStateOf(availableDebtReserveCents > 0L) }
  var notes by remember { mutableStateOf("") }
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Record payment", style = MaterialTheme.typography.headlineSmall)

      if (activeDebts.isEmpty()) {
        Text(
          text = "You don't have any active debts with a balance due yet.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text("Close")
          }
          if (onAddDebt != null) {
            Button(
              onClick = {
                onDismiss()
                onAddDebt()
              },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1.3f)
            ) {
              Text("Add a debt")
            }
          }
        }
      } else {
        if (activeDebts.size > 1) {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            activeDebts.forEach { debt ->
              FilterChip(
                selected = debt.id == selectedDebtId,
                onClick = {
                  selectedDebtId = debt.id
                  errorText = null
                },
                label = { Text(debt.name) }
              )
            }
          }
        }

        if (selectedDebt != null) {
          BaonCard(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentPadding = 14.dp
          ) {
            Text(
              text = selectedDebt.name,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetricRow(
              label = "Amount due",
              value = MoneyFormatter.formatPeso(suggestedInstallment)
            )
            Spacer(modifier = Modifier.height(4.dp))
            MetricRow(
              label = "Balance left",
              value = MoneyFormatter.formatPeso(selectedDebt.remainingBalanceCents),
              boldValue = false
            )
          }

          if (selectedDebt.installmentAmountCents > 0L &&
            selectedDebt.installmentAmountCents < selectedDebt.remainingBalanceCents
          ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = MoneyFormatter.parsePesoToCents(amountText) == selectedDebt.installmentAmountCents,
                onClick = {
                  amountText = MoneyFormatter.formatEditableAmount(selectedDebt.installmentAmountCents)
                  errorText = null
                },
                label = { Text("Installment (${MoneyFormatter.formatPeso(selectedDebt.installmentAmountCents)})") }
              )
              FilterChip(
                selected = MoneyFormatter.parsePesoToCents(amountText) == selectedDebt.remainingBalanceCents,
                onClick = {
                  amountText = MoneyFormatter.formatEditableAmount(selectedDebt.remainingBalanceCents)
                  errorText = null
                },
                label = { Text("Full (${MoneyFormatter.formatPeso(selectedDebt.remainingBalanceCents)})") }
              )
            }
          }
        }

        OutlinedTextField(
          value = amountText,
          onValueChange = {
            amountText = it
            errorText = null
          },
          label = { Text("Amount paid") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          isError = errorText != null,
          supportingText = errorText?.let { msg -> { Text(msg) } }
            ?: { Text("Partial or full payments supported") },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        )

        DatePickerField(
          label = "Payment date",
          dateIso = dateIso,
          onDateChanged = { if (it != null) dateIso = it }
        )

        if (availableDebtReserveCents > 0L) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
              Text("Use debt reserve", style = MaterialTheme.typography.bodyLarge)
              Text(
                text = "${MoneyFormatter.formatPeso(availableDebtReserveCents)} available in reserve",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Switch(checked = useDebtReserve, onCheckedChange = { useDebtReserve = it })
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

        SheetButtonRow(
          confirmText = "Confirm payment",
          onCancel = onDismiss,
          onConfirm = {
            val cents = MoneyFormatter.parsePesoToCents(amountText)
            when {
              cents == null || cents <= 0L -> errorText = "Enter a valid peso amount"
              selectedDebt != null && cents > selectedDebt.remainingBalanceCents ->
                errorText = "Cannot exceed balance (${MoneyFormatter.formatPeso(selectedDebt.remainingBalanceCents)})"
              else -> {
                onConfirm(selectedDebtId, cents, dateIso, useDebtReserve, notes.trim())
                onDismiss()
              }
            }
          }
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustBudgetSheet(
  settings: UserSettingsEntity,
  onSave: (UserSettingsEntity) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var conservativeText by remember(settings.conservativeAllowanceCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.conservativeAllowanceCents))
  }
  var minText by remember(settings.minWeeklyAllowanceCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.minWeeklyAllowanceCents))
  }
  var maxText by remember(settings.maxWeeklyAllowanceCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.maxWeeklyAllowanceCents))
  }
  var schoolDaysText by remember(settings.schoolDaysPerWeek) {
    mutableStateOf(settings.schoolDaysPerWeek.toString())
  }
  var foodPerDayText by remember(settings.foodPerSchoolDayCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.foodPerSchoolDayCents))
  }
  var debtReserveTargetText by remember(settings.weeklyDebtReserveTargetCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.weeklyDebtReserveTargetCents))
  }
  var emergencyTargetText by remember(settings.weeklyEmergencySavingsTargetCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.weeklyEmergencySavingsTargetCents))
  }
  var otherTargetText by remember(settings.weeklyOtherExpensesBudgetCents) {
    mutableStateOf(MoneyFormatter.formatEditableAmount(settings.weeklyOtherExpensesBudgetCents))
  }

  val daysPreview = (schoolDaysText.toIntOrNull() ?: settings.schoolDaysPerWeek).coerceIn(0, 7)
  val perDayPreview = MoneyFormatter.parsePesoToCents(foodPerDayText) ?: settings.foodPerSchoolDayCents
  val foodPreview = FinancialCalculator.calculateWeeklyFoodBudget(daysPreview, perDayPreview)

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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Adjust weekly budget", style = MaterialTheme.typography.headlineSmall)
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = minText,
          onValueChange = { minText = it },
          label = { Text("Min allowance") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = maxText,
          onValueChange = { maxText = it },
          label = { Text("Max allowance") },
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
        label = { Text("Conservative weekly plan") },
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
          label = { Text("Food per day") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
      }
      Text(
        text = "Weekly food budget: ${MoneyFormatter.formatPeso(foodPreview)}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = debtReserveTargetText,
          onValueChange = { debtReserveTargetText = it },
          label = { Text("Debt reserve goal") },
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
        value = otherTargetText,
        onValueChange = { otherTargetText = it },
        label = { Text("Other needs buffer") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      SheetButtonRow(
        confirmText = "Save budget",
        onCancel = onDismiss,
        onConfirm = {
          val days = (schoolDaysText.toIntOrNull() ?: settings.schoolDaysPerWeek).coerceIn(0, 7)
          val perDay = MoneyFormatter.parsePesoToCents(foodPerDayText) ?: settings.foodPerSchoolDayCents
          val minC = MoneyFormatter.parsePesoToCents(minText) ?: settings.minWeeklyAllowanceCents
          val maxC = MoneyFormatter.parsePesoToCents(maxText) ?: settings.maxWeeklyAllowanceCents
          val consC = MoneyFormatter.parsePesoToCents(conservativeText) ?: settings.conservativeAllowanceCents
          val debtResC = MoneyFormatter.parsePesoToCents(debtReserveTargetText) ?: settings.weeklyDebtReserveTargetCents
          val emergC = MoneyFormatter.parsePesoToCents(emergencyTargetText) ?: settings.weeklyEmergencySavingsTargetCents
          val otherC = MoneyFormatter.parsePesoToCents(otherTargetText) ?: settings.weeklyOtherExpensesBudgetCents
          onSave(
            settings.copy(
              minWeeklyAllowanceCents = minC,
              maxWeeklyAllowanceCents = maxOf(minC, maxC),
              conservativeAllowanceCents = consC,
              schoolDaysPerWeek = days,
              foodPerSchoolDayCents = perDay,
              weeklyFoodBudgetCents = FinancialCalculator.calculateWeeklyFoodBudget(days, perDay),
              weeklyDebtReserveTargetCents = debtResC,
              weeklyEmergencySavingsTargetCents = emergC,
              weeklyOtherExpensesBudgetCents = otherC
            )
          )
          onDismiss()
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CreateReserveDialog(
  initialType: ReserveType,
  activeDebts: List<DebtEntity>,
  today: LocalDate,
  onConfirm: (type: ReserveType, amountCents: Long, dateIso: String, targetDebtId: Long?, notes: String) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var reserveType by remember { mutableStateOf(initialType) }
  var amountText by remember {
    mutableStateOf(
      if (initialType == ReserveType.DEBT_REPAYMENT_RESERVE) "150.00" else "50.00"
    )
  }
  var targetDebtId by remember { mutableStateOf<Long?>(null) }
  var dateIso by remember { mutableStateOf(ManilaTime.formatIso(today)) }
  var notes by remember { mutableStateOf("") }
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Set aside money", style = MaterialTheme.typography.headlineSmall)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReserveType.entries.forEach { t ->
          FilterChip(
            selected = reserveType == t,
            onClick = { reserveType = t },
            label = { Text(t.label) }
          )
        }
      }
      OutlinedTextField(
        value = amountText,
        onValueChange = {
          amountText = it
          errorText = null
        },
        label = { Text("Amount") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { msg -> { Text(msg) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE && activeDebts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "For which debt (optional)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            FilterChip(
              selected = targetDebtId == null,
              onClick = { targetDebtId = null },
              label = { Text("General") }
            )
            activeDebts.forEach { debt ->
              FilterChip(
                selected = targetDebtId == debt.id,
                onClick = { targetDebtId = debt.id },
                label = { Text(debt.name) }
              )
            }
          }
        }
      }
      DatePickerField(
        label = "Date",
        dateIso = dateIso,
        onDateChanged = { if (it != null) dateIso = it }
      )
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Note (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      SheetButtonRow(
        confirmText = "Save",
        onCancel = onDismiss,
        onConfirm = {
          val cents = MoneyFormatter.parsePesoToCents(amountText)
          if (cents == null || cents <= 0L) {
            errorText = "Enter a valid peso amount"
          } else {
            onConfirm(reserveType, cents, dateIso, targetDebtId, notes.trim())
            onDismiss()
          }
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DebtEditorDialog(
  existing: DebtEntity?,
  onSave: (DebtEntity) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var name by remember { mutableStateOf(existing?.name ?: "") }
  var lender by remember { mutableStateOf(existing?.lender ?: "") }
  var category by remember { mutableStateOf(existing?.category ?: "Installment Loan") }
  var originalPrincipalText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(existing?.originalPrincipalCents)) }
  var amountReceivedText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(existing?.amountReceivedCents)) }
  var interestText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(existing?.interestCents)) }
  var serviceFeeText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(existing?.serviceFeeCents)) }
  var processingFeeText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(existing?.processingFeeCents)) }
  var totalScheduledText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(existing?.totalScheduledRepaymentCents)) }
  var priorPaidText by remember {
    mutableStateOf(
      if (existing?.isPriorPaidKnown == true) MoneyFormatter.formatEditableAmount(existing.priorPaidCents) else ""
    )
  }
  var remainingBalanceText by remember {
    mutableStateOf(
      if (existing != null) MoneyFormatter.formatEditableAmount(existing.remainingBalanceCents) else ""
    )
  }
  var installmentAmountText by remember {
    mutableStateOf(
      if (existing != null && existing.installmentAmountCents > 0L) {
        MoneyFormatter.formatEditableAmount(existing.installmentAmountCents)
      } else ""
    )
  }
  var remainingCountText by remember {
    mutableStateOf(existing?.remainingInstallmentsCount?.takeIf { it > 0 }?.toString() ?: "")
  }
  var frequency by remember { mutableStateOf(existing?.frequency ?: RepaymentFrequency.UNKNOWN) }
  var customDaysText by remember { mutableStateOf(existing?.customIntervalDays?.toString() ?: "") }
  var nextDueDate by remember { mutableStateOf(existing?.nextDueDate) }
  var dateStatus by remember { mutableStateOf(existing?.dateStatus ?: DueDateStatus.UNCONFIRMED) }
  var notes by remember { mutableStateOf(existing?.notes ?: "") }
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = if (existing == null) "Add debt" else "Edit ${existing.name}",
        style = MaterialTheme.typography.headlineSmall
      )

      OutlinedTextField(
        value = name,
        onValueChange = { name = it; errorText = null },
        label = { Text("Debt name *") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = lender,
        onValueChange = { lender = it },
        label = { Text("Lender or app (e.g. BillEase, SPayLater)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = remainingBalanceText,
        onValueChange = { remainingBalanceText = it; errorText = null },
        label = { Text("Remaining balance *") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = installmentAmountText,
          onValueChange = { installmentAmountText = it },
          label = { Text("Per payment") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = remainingCountText,
          onValueChange = { remainingCountText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Payments left") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(0.9f)
        )
      }

      Text(
        text = "Schedule frequency",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        RepaymentFrequency.entries.forEach { freq ->
          FilterChip(
            selected = frequency == freq,
            onClick = { frequency = freq },
            label = { Text(freq.label) }
          )
        }
      }
      if (frequency == RepaymentFrequency.CUSTOM_DAYS) {
        OutlinedTextField(
          value = customDaysText,
          onValueChange = { customDaysText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Every N days") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        )
      }

      DatePickerField(
        label = "Next due date (leave unset if unknown)",
        dateIso = nextDueDate,
        onDateChanged = { picked ->
          nextDueDate = picked
          if (picked == null && dateStatus == DueDateStatus.CONFIRMED) {
            dateStatus = DueDateStatus.UNCONFIRMED
          }
        },
        allowClear = true
      )

      Text(
        text = "Due date confidence",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        DueDateStatus.entries.forEach { st ->
          FilterChip(
            selected = dateStatus == st,
            onClick = { dateStatus = st },
            label = { Text(st.label) }
          )
        }
      }

      ExpandableSection(
        title = "Loan details (optional)",
        subtitle = "Original principal, fees, prior paid, category & notes"
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = category,
            onValueChange = { category = it },
            label = { Text("Category") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = originalPrincipalText,
              onValueChange = { originalPrincipalText = it },
              label = { Text("Orig. principal") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = amountReceivedText,
              onValueChange = { amountReceivedText = it },
              label = { Text("Received") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = interestText,
              onValueChange = { interestText = it },
              label = { Text("Interest") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = serviceFeeText,
              onValueChange = { serviceFeeText = it },
              label = { Text("Service fee") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = processingFeeText,
              onValueChange = { processingFeeText = it },
              label = { Text("Processing fee") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = totalScheduledText,
              onValueChange = { totalScheduledText = it },
              label = { Text("Total scheduled") },
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            )
          }
          OutlinedTextField(
            value = priorPaidText,
            onValueChange = { priorPaidText = it },
            label = { Text("Already paid before adding") },
            prefix = { Text("₱") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
        }
      }

      if (errorText != null) {
        Text(
          text = errorText!!,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall
        )
      }

      SheetButtonRow(
        confirmText = "Save debt",
        onCancel = onDismiss,
        onConfirm = {
          val remBalance = MoneyFormatter.parsePesoToCents(remainingBalanceText)
          if (name.isBlank()) {
            errorText = "Debt name is required"
            return@SheetButtonRow
          }
          if (remBalance == null || remBalance < 0L) {
            errorText = "Enter a valid remaining balance"
            return@SheetButtonRow
          }
          val instAmt = MoneyFormatter.parsePesoToCents(installmentAmountText) ?: 0L
          val remCount = remainingCountText.toIntOrNull() ?: if (remBalance > 0L && instAmt > 0L) 1 else 0
          val priorPaidParsed = MoneyFormatter.parsePesoToCents(priorPaidText)
          val entity = (existing ?: DebtEntity(
            name = name.trim(),
            lender = lender.ifBlank { "Manual Entry" },
            category = category.ifBlank { "Loan" },
            remainingBalanceCents = remBalance
          )).copy(
            name = name.trim(),
            lender = lender.ifBlank { "Manual Entry" },
            category = category.ifBlank { "Loan" },
            originalPrincipalCents = MoneyFormatter.parsePesoToCents(originalPrincipalText),
            amountReceivedCents = MoneyFormatter.parsePesoToCents(amountReceivedText),
            interestCents = MoneyFormatter.parsePesoToCents(interestText),
            serviceFeeCents = MoneyFormatter.parsePesoToCents(serviceFeeText),
            processingFeeCents = MoneyFormatter.parsePesoToCents(processingFeeText),
            totalScheduledRepaymentCents = MoneyFormatter.parsePesoToCents(totalScheduledText),
            priorPaidCents = priorPaidParsed ?: 0L,
            isPriorPaidKnown = priorPaidParsed != null,
            remainingBalanceCents = remBalance,
            installmentAmountCents = instAmt,
            remainingInstallmentsCount = remCount,
            frequency = frequency,
            customIntervalDays = customDaysText.toIntOrNull(),
            nextDueDate = nextDueDate,
            dateStatus = dateStatus,
            status = if (remBalance == 0L) DebtStatus.PAID else DebtStatus.ACTIVE,
            isManuallyEntered = true,
            notes = notes.trim()
          )
          onSave(entity)
          onDismiss()
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GenerateScheduleDialog(
  debt: DebtEntity,
  today: LocalDate,
  onGenerate: (
    startDateIso: String,
    count: Int,
    installmentAmountCents: Long,
    frequency: RepaymentFrequency,
    customDays: Int?,
    dateStatus: DueDateStatus
  ) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var frequency by remember {
    mutableStateOf(
      if (debt.frequency == RepaymentFrequency.UNKNOWN) RepaymentFrequency.WEEKLY else debt.frequency
    )
  }
  var customDaysText by remember { mutableStateOf(debt.customIntervalDays?.toString() ?: "14") }
  var startDateIso by remember { mutableStateOf(debt.nextDueDate ?: ManilaTime.formatIso(today)) }
  var countText by remember {
    mutableStateOf(debt.remainingInstallmentsCount.coerceAtLeast(1).toString())
  }
  var amountText by remember {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        debt.installmentAmountCents.takeIf { it > 0L } ?: debt.remainingBalanceCents
      )
    )
  }
  var confirmed by remember { mutableStateOf(debt.dateStatus == DueDateStatus.CONFIRMED) }
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text("Payment schedule", style = MaterialTheme.typography.headlineSmall)
      Text(
        text = "Creates upcoming payment rows for ${debt.name} using known schedule intervals only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        RepaymentFrequency.entries.filter { it != RepaymentFrequency.UNKNOWN }.forEach { freq ->
          FilterChip(
            selected = frequency == freq,
            onClick = { frequency = freq },
            label = { Text(freq.label) }
          )
        }
      }
      if (frequency == RepaymentFrequency.CUSTOM_DAYS) {
        OutlinedTextField(
          value = customDaysText,
          onValueChange = { customDaysText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Interval (days)") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth()
        )
      }
      DatePickerField(
        label = "First due date",
        dateIso = startDateIso,
        onDateChanged = { if (it != null) startDateIso = it }
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = countText,
          onValueChange = { countText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Payments") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = amountText,
          onValueChange = { amountText = it; errorText = null },
          label = { Text("Each") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
        Text("Dates are confirmed with lender", style = MaterialTheme.typography.bodySmall)
      }
      if (errorText != null) {
        Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      SheetButtonRow(
        confirmText = "Save schedule",
        onCancel = onDismiss,
        onConfirm = {
          val count = countText.toIntOrNull()
          val amt = MoneyFormatter.parsePesoToCents(amountText)
          if (count == null || count <= 0 || amt == null || amt <= 0L) {
            errorText = "Enter valid payment count and peso amount"
            return@SheetButtonRow
          }
          onGenerate(
            startDateIso,
            count,
            amt,
            frequency,
            customDaysText.toIntOrNull(),
            if (confirmed) DueDateStatus.CONFIRMED else DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
          )
          onDismiss()
        }
      )
    }
  }
}

@Composable
fun ConfirmDueDateDialog(
  debt: DebtEntity,
  today: LocalDate,
  onConfirm: (dateIso: String, generateSchedule: Boolean) -> Unit,
  onDismiss: () -> Unit
) {
  var dateIso by remember { mutableStateOf(debt.nextDueDate ?: ManilaTime.formatIso(today)) }
  var generateSchedule by remember {
    mutableStateOf(debt.frequency != RepaymentFrequency.UNKNOWN && debt.remainingInstallmentsCount > 1)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Confirm next due date") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Confirm the next due date shown in your lender app for ${debt.name}.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DatePickerField(
          label = "Confirmed due date",
          dateIso = dateIso,
          onDateChanged = { if (it != null) dateIso = it }
        )
        if (debt.frequency != RepaymentFrequency.UNKNOWN && debt.remainingInstallmentsCount > 1) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = generateSchedule, onCheckedChange = { generateSchedule = it })
            Text(
              "Also update ${debt.remainingInstallmentsCount} ${debt.frequency.label.lowercase()} installments",
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onConfirm(dateIso, generateSchedule)
          onDismiss()
        }
      ) {
        Text("Confirm date")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectTransactionDialog(
  tx: TransactionEntity,
  onSaveEdit: (newAmountCents: Long, newTitle: String, newDateIso: String, newNotes: String, reason: String) -> Unit,
  onVoid: (reason: String) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var title by remember { mutableStateOf(tx.title) }
  var amountText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(tx.amountCents)) }
  var dateIso by remember { mutableStateOf(tx.date) }
  var notes by remember { mutableStateOf(tx.notes) }
  var reason by remember { mutableStateOf("Correction of entry") }
  var showVoidConfirm by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }

  if (showVoidConfirm) {
    AlertDialog(
      onDismissRequest = { showVoidConfirm = false },
      title = { Text("Void this transaction?") },
      text = {
        Text(
          "Voiding ${tx.title} (${MoneyFormatter.formatPeso(tx.amountCents)}) reverts its effect on linked debt balances, installments, or reserves while keeping an audit record."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onVoid(reason.ifBlank { "Voided transaction" })
            onDismiss()
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
          Text("Void & revert")
        }
      },
      dismissButton = {
        TextButton(onClick = { showVoidConfirm = false }) { Text("Back") }
      }
    )
    return
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text("Edit transaction", style = MaterialTheme.typography.headlineSmall)
      Text(
        text = "Changes update linked loan balances, installments, or reserves automatically.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Description") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it; errorText = null },
        label = { Text("Amount") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { msg -> { Text(msg) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      DatePickerField(
        label = "Date",
        dateIso = dateIso,
        onDateChanged = { if (it != null) dateIso = it }
      )
      OutlinedTextField(
        value = reason,
        onValueChange = { reason = it },
        label = { Text("Reason") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Note") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedButton(
        onClick = { showVoidConfirm = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text("Void transaction", fontWeight = FontWeight.Medium)
      }
      SheetButtonRow(
        confirmText = "Save changes",
        onCancel = onDismiss,
        onConfirm = {
          val cents = MoneyFormatter.parsePesoToCents(amountText)
          if (cents == null || cents <= 0L) {
            errorText = "Enter a valid peso amount"
          } else {
            onSaveEdit(cents, title, dateIso, notes, reason)
            onDismiss()
          }
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UseEmergencySavingsDialog(
  availableSavingsCents: Long,
  today: LocalDate,
  onConfirm: (
    amountCents: Long,
    dateIso: String,
    logAsExpense: Boolean,
    title: String,
    notes: String
  ) -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val suggestedDefault = minOf(availableSavingsCents, 5_000L).takeIf { it > 0L } ?: availableSavingsCents
  var amountText by remember { mutableStateOf(MoneyFormatter.formatEditableAmount(suggestedDefault)) }
  var logAsExpense by remember { mutableStateOf(true) }
  var title by remember { mutableStateOf("") }
  var dateIso by remember { mutableStateOf(ManilaTime.formatIso(today)) }
  var notes by remember { mutableStateOf("") }
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
        .padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("Use emergency savings", style = MaterialTheme.typography.headlineSmall)
      Text(
        text = "Available balance: ${MoneyFormatter.formatPeso(availableSavingsCents)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = logAsExpense,
          onClick = { logAsExpense = true },
          label = { Text("Spend as emergency expense") }
        )
        FilterChip(
          selected = !logAsExpense,
          onClick = { logAsExpense = false },
          label = { Text("Move to this week's cash") }
        )
      }
      val quickAmounts = listOfNotNull(
        5_000L.takeIf { it < availableSavingsCents },
        10_000L.takeIf { it < availableSavingsCents },
        availableSavingsCents
      ).distinct()
      if (quickAmounts.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          quickAmounts.forEach { cents ->
            val label = if (cents == availableSavingsCents) {
              "All (${MoneyFormatter.formatPesoCompact(cents)})"
            } else {
              MoneyFormatter.formatPesoCompact(cents)
            }
            FilterChip(
              selected = MoneyFormatter.parsePesoToCents(amountText) == cents,
              onClick = {
                amountText = MoneyFormatter.formatEditableAmount(cents)
                errorText = null
              },
              label = { Text(label) }
            )
          }
        }
      }
      OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it; errorText = null },
        label = { Text("Amount to withdraw") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { msg -> { Text(msg) } },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text(if (logAsExpense) "What was it for? (optional)" else "Label (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      DatePickerField(
        label = "Date",
        dateIso = dateIso,
        onDateChanged = { if (it != null) dateIso = it }
      )
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Note (optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      SheetButtonRow(
        confirmText = if (logAsExpense) "Use savings" else "Move to cash",
        onCancel = onDismiss,
        onConfirm = {
          val cents = MoneyFormatter.parsePesoToCents(amountText)
          when {
            cents == null || cents <= 0L -> errorText = "Enter a valid peso amount"
            cents > availableSavingsCents -> errorText = "Cannot exceed ${MoneyFormatter.formatPeso(availableSavingsCents)}"
            else -> {
              onConfirm(cents, dateIso, logAsExpense, title.trim(), notes.trim())
              onDismiss()
            }
          }
        }
      )
    }
  }
}

@Composable
fun ConfirmMarkInstallmentDialog(
  debtName: String,
  installment: InstallmentEntity,
  availableDebtReserveCents: Long,
  onConfirm: (useDebtReserve: Boolean) -> Unit,
  onDismiss: () -> Unit
) {
  val remainingDue = (installment.amountDueCents - installment.paidAmountCents).coerceAtLeast(0L)
  var useReserve by remember { mutableStateOf(availableDebtReserveCents > 0L) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Mark payment #${installment.installmentNumber} paid?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "$debtName · ${MoneyFormatter.formatPeso(remainingDue)} (${ManilaTime.formatDisplay(installment.dueDate)})",
          style = MaterialTheme.typography.bodyMedium
        )
        if (availableDebtReserveCents > 0L) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text("Use debt reserve", style = MaterialTheme.typography.labelLarge)
              Text(
                text = "${MoneyFormatter.formatPeso(availableDebtReserveCents)} reserved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Switch(checked = useReserve, onCheckedChange = { useReserve = it })
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onConfirm(availableDebtReserveCents > 0L && useReserve)
          onDismiss()
        }
      ) {
        Text("Confirm paid")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}
