package com.baonplan.finance.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.ObligationRole
import com.baonplan.finance.domain.ocr.DebtDuplicateDetector
import com.baonplan.finance.domain.ocr.DebtImportDraft
import com.baonplan.finance.domain.ocr.DebtImportValidator
import com.baonplan.finance.domain.ocr.ExtractedField
import com.baonplan.finance.domain.ocr.ExtractedInstallmentRow
import com.baonplan.finance.domain.ocr.ExtractedInstallmentStatus
import com.baonplan.finance.domain.ocr.ExtractionConfidence
import com.baonplan.finance.domain.ocr.FieldAlternative
import com.baonplan.finance.domain.ocr.FieldOrigin
import com.baonplan.finance.domain.ocr.ImportSeverity
import com.baonplan.finance.domain.ocr.TextCorrectionAudit
import com.baonplan.finance.ui.theme.LocalBaonColors
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.TransactionType
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
  var selectedDebtId by remember(activeDebts, initialDebtId) {
    val preferredId = initialDebtId?.takeIf { id -> activeDebts.any { it.id == id } }
      ?: activeDebts.firstOrNull()?.id
      ?: 0L
    mutableLongStateOf(preferredId)
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
              selectedDebt == null -> errorText = "Select an active debt first"
              cents == null || cents <= 0L -> errorText = "Enter a valid peso amount"
              cents > selectedDebt.remainingBalanceCents ->
                errorText = "Cannot exceed balance (${MoneyFormatter.formatPeso(selectedDebt.remainingBalanceCents)})"
              else -> {
                onConfirm(selectedDebt.id, cents, dateIso, useDebtReserve, notes.trim())
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
  var errorText by remember { mutableStateOf<String?>(null) }

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
          onValueChange = { minText = it; errorText = null },
          label = { Text("Min allowance") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = maxText,
          onValueChange = { maxText = it; errorText = null },
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
        onValueChange = { conservativeText = it; errorText = null },
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
          onValueChange = { schoolDaysText = it.filter { ch -> ch.isDigit() }; errorText = null },
          label = { Text("School days / wk") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = foodPerDayText,
          onValueChange = { foodPerDayText = it; errorText = null },
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
          onValueChange = { debtReserveTargetText = it; errorText = null },
          label = { Text("Debt reserve goal") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = emergencyTargetText,
          onValueChange = { emergencyTargetText = it; errorText = null },
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
        onValueChange = { otherTargetText = it; errorText = null },
        label = { Text("Other needs buffer") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      if (errorText != null) {
        Text(
          text = errorText!!,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall
        )
      }
      SheetButtonRow(
        confirmText = "Save budget",
        onCancel = onDismiss,
        onConfirm = {
          val days = schoolDaysText.toIntOrNull()
          val perDay = MoneyFormatter.parsePesoToCents(foodPerDayText)
          val minC = MoneyFormatter.parsePesoToCents(minText)
          val maxC = MoneyFormatter.parsePesoToCents(maxText)
          val consC = MoneyFormatter.parsePesoToCents(conservativeText)
          val debtResC = MoneyFormatter.parsePesoToCents(debtReserveTargetText)
          val emergC = MoneyFormatter.parsePesoToCents(emergencyTargetText)
          val otherC = MoneyFormatter.parsePesoToCents(otherTargetText)
          when {
            days == null || days !in 0..7 ->
              errorText = "School days per week must be between 0 and 7"
            listOf(perDay, minC, maxC, consC, debtResC, emergC, otherC).any { it == null || it < 0L } ->
              errorText = "Enter valid peso amounts (0 or greater)"
            maxC!! < minC!! ->
              errorText = "Max allowance must be at least min allowance"
            else -> {
              onSave(
                settings.copy(
                  minWeeklyAllowanceCents = minC,
                  maxWeeklyAllowanceCents = maxC,
                  conservativeAllowanceCents = consC!!,
                  schoolDaysPerWeek = days,
                  foodPerSchoolDayCents = perDay!!,
                  weeklyFoodBudgetCents = FinancialCalculator.calculateWeeklyFoodBudget(days, perDay),
                  weeklyDebtReserveTargetCents = debtResC!!,
                  weeklyEmergencySavingsTargetCents = emergC!!,
                  weeklyOtherExpensesBudgetCents = otherC!!
                )
              )
              onDismiss()
            }
          }
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
  onDismiss: () -> Unit,
  prefillDraft: DebtImportDraft? = null,
  existingDebts: List<DebtEntity> = emptyList(),
  existingInstallments: List<InstallmentEntity> = emptyList(),
  partialScanWarnings: List<String> = emptyList(),
  isSavingImport: Boolean = false,
  onSaveImported: ((debt: DebtEntity, scheduleRows: List<InstallmentEntity>, attachScreenshots: Boolean) -> Unit)? = null
) {
  val scanDraft = if (existing == null) prefillDraft else null
  val isScanReview = scanDraft != null
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val stateKey: Any = existing?.id ?: scanDraft?.candidateId ?: "manual_new"

  var name by remember(stateKey) {
    mutableStateOf(existing?.name ?: prefillDraft?.loanName?.value.orEmpty())
  }
  var lender by remember(stateKey) {
    mutableStateOf(existing?.lender ?: prefillDraft?.lender?.value.orEmpty())
  }
  var category by remember(stateKey) {
    mutableStateOf(
      existing?.category
        ?: prefillDraft?.loanType?.value?.takeIf { it.isNotBlank() }
        ?: "Installment Loan"
    )
  }
  var productClassification by remember(stateKey) {
    mutableStateOf(
      existing?.productClassification
        ?: prefillDraft?.productClassification?.value
        ?: LoanProductClassification.UNKNOWN
    )
  }
  var applicationProvider by remember(stateKey) {
    mutableStateOf(existing?.applicationProvider ?: prefillDraft?.applicationProvider?.value.orEmpty())
  }
  var actualCreditor by remember(stateKey) {
    mutableStateOf(existing?.actualCreditor ?: prefillDraft?.actualCreditor?.value.orEmpty())
  }
  var loanReferenceId by remember(stateKey) {
    mutableStateOf(existing?.loanReferenceId ?: prefillDraft?.loanReferenceId?.value.orEmpty())
  }
  var loanBookingDateIso by remember(stateKey) {
    mutableStateOf(existing?.loanBookingDateIso ?: prefillDraft?.loanBookingDateIso?.value)
  }
  var originalPrincipalText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.originalPrincipalCents ?: prefillDraft?.originalPrincipalCents?.value
      )
    )
  }
  var disbursedAmountText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.disbursedAmountCents ?: prefillDraft?.disbursedAmountCents?.value
      )
    )
  }
  var amountReceivedText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.amountReceivedCents ?: prefillDraft?.amountReceivedCents?.value
      )
    )
  }
  var interestText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.interestCents ?: prefillDraft?.interestCents?.value
      )
    )
  }
  var serviceFeeText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.serviceFeeCents ?: prefillDraft?.serviceFeeCents?.value
      )
    )
  }
  var processingFeeText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.processingFeeCents ?: prefillDraft?.processingFeeCents?.value
      )
    )
  }
  var otherFeesText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.otherChargesCents ?: prefillDraft?.otherFeesCents?.value
      )
    )
  }
  var pastDueAmountText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.pastDueAmountCents ?: prefillDraft?.pastDueAmountCents?.value
      )
    )
  }
  var unpaidChargesText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.unpaidChargesCents ?: prefillDraft?.unpaidChargesCents?.value
      )
    )
  }
  var creditLimitText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.creditLimitCents ?: prefillDraft?.creditLimitCents?.value
      )
    )
  }
  var availableCreditText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.availableCreditCents ?: prefillDraft?.availableCreditCents?.value
      )
    )
  }
  var usedCreditText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.usedCreditCents ?: prefillDraft?.usedCreditCents?.value
      )
    )
  }
  var statementBalanceText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.statementBalanceCents ?: prefillDraft?.statementBalanceCents?.value
      )
    )
  }
  var minimumDueText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.minimumDueCents ?: prefillDraft?.minimumDueCents?.value
      )
    )
  }
  var statementDateIso by remember(stateKey) {
    mutableStateOf(existing?.statementDateIso ?: prefillDraft?.statementDateIso?.value)
  }
  var statementPeriod by remember(stateKey) {
    mutableStateOf(existing?.statementPeriod ?: prefillDraft?.statementPeriod?.value.orEmpty())
  }
  var totalScheduledText by remember(stateKey) {
    mutableStateOf(
      MoneyFormatter.formatEditableAmount(
        existing?.totalScheduledRepaymentCents ?: prefillDraft?.totalRepaymentCents?.value
      )
    )
  }
  var priorPaidText by remember(stateKey) {
    mutableStateOf(
      when {
        existing?.isPriorPaidKnown == true -> MoneyFormatter.formatEditableAmount(existing.priorPaidCents)
        prefillDraft?.calculatedPaidScheduleSumCents?.value != null &&
          prefillDraft.calculatedPaidScheduleSumCents.value > 0L ->
          MoneyFormatter.formatEditableAmount(prefillDraft.calculatedPaidScheduleSumCents.value)
        else -> ""
      }
    )
  }
  val initialBalanceField: ExtractedField<Long>? = remember(stateKey) {
    when {
      prefillDraft == null -> null
      prefillDraft.productClassification.value == LoanProductClassification.REVOLVING_CREDIT ->
        when {
          prefillDraft.statementBalanceCents.value != null -> prefillDraft.statementBalanceCents
          prefillDraft.usedCreditCents.value != null -> prefillDraft.usedCreditCents
          prefillDraft.outstandingBalanceCents.value != null -> prefillDraft.outstandingBalanceCents
          prefillDraft.amountCurrentlyDueCents.value != null -> prefillDraft.amountCurrentlyDueCents
          else -> prefillDraft.statementBalanceCents
        }
      prefillDraft.outstandingBalanceCents.value != null -> prefillDraft.outstandingBalanceCents
      prefillDraft.remainingInstallmentTotalCents.value != null -> prefillDraft.remainingInstallmentTotalCents
      prefillDraft.calculatedUnpaidScheduleSumCents.value != null && prefillDraft.calculatedUnpaidScheduleSumCents.value > 0L ->
        prefillDraft.calculatedUnpaidScheduleSumCents
      prefillDraft.amountCurrentlyDueCents.value != null -> prefillDraft.amountCurrentlyDueCents
      else -> prefillDraft.outstandingBalanceCents
    }
  }
  val initialBalanceText = remember(stateKey) {
    when {
      existing != null -> MoneyFormatter.formatEditableAmount(existing.remainingBalanceCents)
      initialBalanceField?.value != null -> MoneyFormatter.formatEditableAmount(initialBalanceField.value)
      else -> ""
    }
  }
  var remainingBalanceText by remember(stateKey) { mutableStateOf(initialBalanceText) }
  val initialInstallmentText = remember(stateKey) {
    when {
      existing != null && existing.installmentAmountCents > 0L ->
        MoneyFormatter.formatEditableAmount(existing.installmentAmountCents)
      prefillDraft?.installmentAmountCents?.value != null && prefillDraft.installmentAmountCents.value > 0L ->
        MoneyFormatter.formatEditableAmount(prefillDraft.installmentAmountCents.value)
      else -> ""
    }
  }
  var installmentAmountText by remember(stateKey) { mutableStateOf(initialInstallmentText) }
  var originalCountText by remember(stateKey) {
    mutableStateOf(
      existing?.originalInstallmentsCount?.toString()
        ?: prefillDraft?.originalInstallmentCount?.value?.toString().orEmpty()
    )
  }
  var paidCountText by remember(stateKey) {
    mutableStateOf(
      existing?.paidInstallmentsCount?.toString()
        ?: prefillDraft?.paidInstallmentsCount?.value?.toString().orEmpty()
    )
  }
  val initialRemainingCountText = remember(stateKey) {
    existing?.remainingInstallmentsCount?.takeIf { it > 0 }?.toString()
      ?: prefillDraft?.remainingInstallmentsCount?.value?.toString().orEmpty()
  }
  var remainingCountText by remember(stateKey) { mutableStateOf(initialRemainingCountText) }
  var frequency by remember(stateKey) {
    mutableStateOf(
      existing?.frequency
        ?: prefillDraft?.frequency?.value
        ?: RepaymentFrequency.UNKNOWN
    )
  }
  var customDaysText by remember(stateKey) {
    mutableStateOf(
      existing?.customIntervalDays?.toString()
        ?: prefillDraft?.customIntervalDays?.value?.toString().orEmpty()
    )
  }
  var nextDueDate by remember(stateKey) {
    mutableStateOf(existing?.nextDueDate ?: prefillDraft?.nextDueDateIso?.value)
  }
  var finalDueDate by remember(stateKey) {
    mutableStateOf(prefillDraft?.finalDueDateIso?.value)
  }
  var dateStatus by remember(stateKey) {
    mutableStateOf(
      existing?.dateStatus
        ?: prefillDraft?.dueDateStatus
        ?: DueDateStatus.UNCONFIRMED
    )
  }
  var linkedParentDebtId by remember(stateKey) {
    mutableStateOf<Long?>(existing?.parentDebtId)
  }
  var notes by remember(stateKey) { mutableStateOf(existing?.notes ?: "") }
  var errorText by remember(stateKey) { mutableStateOf<String?>(null) }

  val editableScheduleRows = remember(stateKey) {
    mutableStateListOf<EditableScheduleRowState>().apply {
      prefillDraft?.installmentRows.orEmpty().forEach { row ->
        add(EditableScheduleRowState.fromExtracted(row))
      }
    }
  }
  var editingScheduleDateRowId by remember(stateKey) { mutableStateOf<String?>(null) }
  var explanationTarget by remember(stateKey) { mutableStateOf<ExplanationSheetTarget?>(null) }
  var allowDuplicateOverride by remember(stateKey) { mutableStateOf(false) }
  var attachScreenshots by remember(stateKey) { mutableStateOf(false) }
  val isSaving = isSavingImport

  val scrollState = rememberScrollState()
  val placeholderIfScan: (@Composable () -> Unit)? = if (isScanReview) {
    { Text("Not detected") }
  } else {
    null
  }

  LaunchedEffect(errorText) {
    if (errorText != null) {
      scrollState.animateScrollTo(scrollState.maxValue)
    }
  }

  val parsedRemainingBalance = MoneyFormatter.parsePesoToCents(remainingBalanceText)
  val parsedInstallmentAmount = MoneyFormatter.parsePesoToCents(installmentAmountText)
  val parsedOrigPrincipal = MoneyFormatter.parsePesoToCents(originalPrincipalText)
  val parsedDisbursedAmount = MoneyFormatter.parsePesoToCents(disbursedAmountText)
  val parsedAmountReceived = MoneyFormatter.parsePesoToCents(amountReceivedText)
  val parsedInterest = MoneyFormatter.parsePesoToCents(interestText)
  val parsedServiceFee = MoneyFormatter.parsePesoToCents(serviceFeeText)
  val parsedProcessingFee = MoneyFormatter.parsePesoToCents(processingFeeText)
  val parsedOtherFees = MoneyFormatter.parsePesoToCents(otherFeesText)
  val parsedPastDueAmount = MoneyFormatter.parsePesoToCents(pastDueAmountText)
  val parsedUnpaidCharges = MoneyFormatter.parsePesoToCents(unpaidChargesText)
  val parsedCreditLimit = MoneyFormatter.parsePesoToCents(creditLimitText)
  val parsedAvailableCredit = MoneyFormatter.parsePesoToCents(availableCreditText)
  val parsedUsedCredit = MoneyFormatter.parsePesoToCents(usedCreditText)
  val parsedStatementBalance = MoneyFormatter.parsePesoToCents(statementBalanceText)
  val parsedMinimumDue = MoneyFormatter.parsePesoToCents(minimumDueText)
  val parsedTotalScheduled = MoneyFormatter.parsePesoToCents(totalScheduledText)
  val parsedOrigCount = originalCountText.toIntOrNull()
  val parsedPaidCount = paidCountText.toIntOrNull()
  val parsedRemCount = remainingCountText.toIntOrNull()
  val parsedCustomDays = if (frequency == RepaymentFrequency.CUSTOM_DAYS) {
    customDaysText.toIntOrNull()
  } else {
    null
  }

  val currentScheduleAsExtracted = if (isScanReview) {
    editableScheduleRows.map { it.toExtractedRow() }
  } else {
    emptyList()
  }

  val liveIssues = if (scanDraft != null) {
    DebtImportValidator.validateValues(
      debtName = name,
      productClassification = productClassification,
      isSchedulePartial = scanDraft.isSchedulePartial,
      remainingBalanceCents = parsedRemainingBalance,
      explicitBalanceCents = scanDraft.outstandingBalanceCents.value ?: parsedRemainingBalance,
      explicitAmountCurrentlyDueCents = scanDraft.amountCurrentlyDueCents.value,
      explicitRemainingInstallmentTotalCents = scanDraft.remainingInstallmentTotalCents.value,
      originalPrincipalCents = parsedOrigPrincipal,
      amountReceivedCents = parsedAmountReceived,
      interestCents = parsedInterest,
      serviceFeeCents = parsedServiceFee,
      processingFeeCents = parsedProcessingFee,
      otherFeesCents = parsedOtherFees,
      totalScheduledCents = parsedTotalScheduled,
      creditLimitCents = parsedCreditLimit,
      availableCreditCents = parsedAvailableCredit,
      usedCreditCents = parsedUsedCredit,
      statementBalanceCents = parsedStatementBalance,
      minimumDueCents = parsedMinimumDue,
      installmentAmountCents = parsedInstallmentAmount,
      originalInstallmentCount = parsedOrigCount,
      paidInstallmentsCount = parsedPaidCount,
      remainingInstallmentsCount = parsedRemCount,
      frequency = frequency,
      customIntervalDays = parsedCustomDays,
      nextDueDateIso = nextDueDate,
      dueDateStatus = dateStatus,
      scheduleRows = currentScheduleAsExtracted,
      requireNonBlankName = true
    )
  } else {
    emptyList()
  }

  val liveDuplicates = if (scanDraft != null) {
    DebtDuplicateDetector.findPotentialDuplicates(
      lender = lender,
      loanName = name,
      loanReferenceId = loanReferenceId,
      productClassification = productClassification,
      remainingBalanceCents = parsedRemainingBalance,
      originalPrincipalCents = parsedOrigPrincipal,
      installmentAmountCents = parsedInstallmentAmount,
      nextDueDateIso = nextDueDate,
      scheduleDates = currentScheduleAsExtracted.mapNotNull { it.dueDateIso }.toSet(),
      existingDebts = existingDebts,
      existingInstallments = existingInstallments
    )
  } else {
    emptyList()
  }

  val autoExpandDetails = isScanReview && listOf(
    originalPrincipalText,
    amountReceivedText,
    interestText,
    serviceFeeText,
    processingFeeText,
    otherFeesText,
    totalScheduledText,
    priorPaidText,
    originalCountText,
    paidCountText,
    loanReferenceId
  ).any { it.isNotBlank() } || (isScanReview && finalDueDate != null)

  ModalBottomSheet(
    onDismissRequest = { if (!isSaving) onDismiss() },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(scrollState)
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      if (scanDraft != null) {
        val pagesLabel = scanDraft.sourceScreenshotIndices.joinToString(", ") { "#${it + 1}" }
        Text(
          text = "Review scanned debt",
          style = MaterialTheme.typography.headlineSmall
        )
        Text(
          text = "Extracted from screenshot(s) $pagesLabel. Every field is editable — nothing is saved until you tap Save debt.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (partialScanWarnings.isNotEmpty()) {
          InfoCallout(
            icon = Icons.Outlined.WarningAmber,
            title = "Partial read notice",
            body = partialScanWarnings.joinToString("\n"),
            tone = BadgeTone.WARNING
          )
        }
        if (scanDraft.mergeNotes.isNotEmpty()) {
          InfoCallout(
            icon = Icons.Outlined.Info,
            title = "Multi-screenshot summary",
            body = scanDraft.mergeNotes.joinToString("\n"),
            tone = BadgeTone.NEUTRAL
          )
        }
        if (liveDuplicates.isNotEmpty()) {
          val matchSummary = liveDuplicates.joinToString("\n") { dup ->
            "• ${dup.debtName} (${dup.lender} · ${MoneyFormatter.formatPeso(dup.remainingBalanceCents)} left): ${dup.reasons.joinToString(", ")}"
          }
          InfoCallout(
            icon = Icons.Outlined.WarningAmber,
            title = "Possible duplicate debt detected",
            body = "$matchSummary\n\nCancel if you already added this loan, or confirm below if this is a separate debt.",
            tone = BadgeTone.WARNING
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                allowDuplicateOverride = !allowDuplicateOverride
                errorText = null
              },
            verticalAlignment = Alignment.CenterVertically
          ) {
            Checkbox(
              checked = allowDuplicateOverride,
              onCheckedChange = {
                allowDuplicateOverride = it
                errorText = null
              }
            )
            Text(
              text = "This is a separate loan — allow saving anyway",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface
            )
          }
        }
      } else {
        Text(
          text = if (existing == null) "Add debt" else "Edit ${existing.name}",
          style = MaterialTheme.typography.headlineSmall
        )
      }

      OutlinedTextField(
        value = name,
        onValueChange = { name = it; errorText = null },
        label = { Text("Debt name *") },
        placeholder = placeholderIfScan,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      if (scanDraft != null) {
        val displayNameField = effectiveScanField(scanDraft.loanName, name, prefillDraft?.loanName?.value.orEmpty())
        ScanFieldCaption(
          field = displayNameField,
          currentText = name,
          onExplain = {
            explanationTarget = buildExplanationTarget(
              title = "Debt name",
              field = displayNameField,
              currentDisplay = name.ifBlank { null },
              formatAlt = { it },
              onApplyAlt = { alt -> name = alt; errorText = null }
            )
          }
        )
      }

      OutlinedTextField(
        value = lender,
        onValueChange = { lender = it },
        label = { Text("Lender or app (e.g. BillEase, SPayLater)") },
        placeholder = placeholderIfScan,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      if (scanDraft != null) {
        val displayLenderField = effectiveScanField(scanDraft.lender, lender, prefillDraft?.lender?.value.orEmpty())
        ScanFieldCaption(
          field = displayLenderField,
          currentText = lender,
          onExplain = {
            explanationTarget = buildExplanationTarget(
              title = "Lender / Provider",
              field = displayLenderField,
              currentDisplay = lender.ifBlank { null },
              formatAlt = { it },
              onApplyAlt = { alt -> lender = alt }
            )
          }
        )
      }

      if (isScanReview || productClassification != LoanProductClassification.UNKNOWN) {
        Text(
          text = "Product type",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          LoanProductClassification.entries.forEach { prod ->
            FilterChip(
              selected = productClassification == prod,
              onClick = {
                productClassification = prod
                if (category.isBlank() || category == "Installment Loan") {
                  category = prod.label
                }
                errorText = null
              },
              label = { Text(prod.label, style = MaterialTheme.typography.labelSmall) }
            )
          }
        }
      }

      val candidateParentDebts = remember(existingDebts, lender, existing?.id) {
        val normLender = lender.trim().lowercase()
        existingDebts.filter { candidate ->
          candidate.id != existing?.id &&
            candidate.status == DebtStatus.ACTIVE &&
            candidate.parentDebtId == null &&
            (normLender.isBlank() ||
              candidate.lender.lowercase().contains(normLender) ||
              normLender.contains(candidate.lender.lowercase()))
        }
      }
      if ((productClassification == LoanProductClassification.BNPL_PAY_LATER ||
          productClassification == LoanProductClassification.CREDIT_BILL_INSTALLMENT ||
          scanDraft?.obligationRole == ObligationRole.BILLING_STATEMENT ||
          scanDraft?.obligationRole == ObligationRole.CONVERTED_INSTALLMENT) &&
        candidateParentDebts.isNotEmpty()
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, LocalBaonColors.current.divider),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "Avoid double-counting in total debt",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "If this statement or converted plan belongs to an existing credit line already tracked in BaonPlan, link it below so its balance is not counted twice.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              FilterChip(
                selected = linkedParentDebtId == null,
                onClick = { linkedParentDebtId = null },
                label = { Text("Standalone (count in totals)", style = MaterialTheme.typography.labelSmall) }
              )
              candidateParentDebts.forEach { parent ->
                FilterChip(
                  selected = linkedParentDebtId == parent.id,
                  onClick = { linkedParentDebtId = parent.id },
                  label = { Text("Link to ${parent.name}", style = MaterialTheme.typography.labelSmall) }
                )
              }
            }
          }
        }
      }

      OutlinedTextField(
        value = remainingBalanceText,
        onValueChange = { remainingBalanceText = it; errorText = null },
        label = {
          Text(
            if (productClassification == LoanProductClassification.REVOLVING_CREDIT) {
              "Remaining debt balance *"
            } else {
              "Remaining balance *"
            }
          )
        },
        placeholder = placeholderIfScan,
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      if (scanDraft != null) {
        val rawBalField = initialBalanceField ?: scanDraft.outstandingBalanceCents
        val primaryBalField = effectiveScanField(rawBalField, remainingBalanceText, initialBalanceText)
        ScanFieldCaption(
          field = primaryBalField,
          currentText = remainingBalanceText,
          onExplain = {
            explanationTarget = buildExplanationTarget(
              title = "Remaining balance",
              field = primaryBalField,
              currentDisplay = parsedRemainingBalance?.let { MoneyFormatter.formatPeso(it) },
              formatAlt = { MoneyFormatter.formatPeso(it) },
              onApplyAlt = { alt ->
                remainingBalanceText = MoneyFormatter.formatEditableAmount(alt)
                errorText = null
              }
            )
          }
        )

        if (productClassification == LoanProductClassification.REVOLVING_CREDIT ||
          creditLimitText.isNotBlank() ||
          availableCreditText.isNotBlank() ||
          statementBalanceText.isNotBlank() ||
          minimumDueText.isNotBlank()
        ) {
          RevolvingCreditReviewCard(
            creditLimitText = creditLimitText,
            onCreditLimitChange = { newVal: String -> creditLimitText = newVal; errorText = null },
            availableCreditText = availableCreditText,
            onAvailableCreditChange = { newVal: String -> availableCreditText = newVal; errorText = null },
            usedCreditText = usedCreditText,
            onUsedCreditChange = { newVal: String -> usedCreditText = newVal; errorText = null },
            statementBalanceText = statementBalanceText,
            onStatementBalanceChange = { newVal: String -> statementBalanceText = newVal; errorText = null },
            minimumDueText = minimumDueText,
            onMinimumDueChange = { newVal: String -> minimumDueText = newVal; errorText = null },
            onApplyAsRemainingBalance = { cents: Long ->
              remainingBalanceText = MoneyFormatter.formatEditableAmount(cents)
              errorText = null
            }
          )
        }

        BalanceComparisonReviewCard(
          extractedBalanceCents = scanDraft.outstandingBalanceCents.value,
          extractedAmountCurrentlyDueCents = scanDraft.amountCurrentlyDueCents.value,
          extractedRemainingInstallmentTotalCents = scanDraft.remainingInstallmentTotalCents.value,
          calculatedUnpaidScheduleSumCents = editableScheduleRows
            .filter { it.status.isUnpaidObligation }
            .mapNotNull { MoneyFormatter.parsePesoToCents(it.amountText) }
            .takeIf { it.isNotEmpty() }
            ?.let { MoneyFormatter.sumCentsExact(it) },
          onApplyAmount = { cents ->
            remainingBalanceText = MoneyFormatter.formatEditableAmount(cents)
            errorText = null
          }
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
          OutlinedTextField(
            value = installmentAmountText,
            onValueChange = { installmentAmountText = it; errorText = null },
            label = { Text("Per payment") },
            placeholder = placeholderIfScan,
            prefix = { Text("₱") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          if (scanDraft != null) {
            val displayInstField = effectiveScanField(scanDraft.installmentAmountCents, installmentAmountText, initialInstallmentText)
            ScanFieldCaption(
              field = displayInstField,
              currentText = installmentAmountText,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Installment amount",
                  field = displayInstField,
                  currentDisplay = parsedInstallmentAmount?.let { MoneyFormatter.formatPeso(it) },
                  formatAlt = { MoneyFormatter.formatPeso(it) },
                  onApplyAlt = { alt ->
                    installmentAmountText = MoneyFormatter.formatEditableAmount(alt)
                    errorText = null
                  }
                )
              }
            )
          }
        }
        Column(modifier = Modifier.weight(0.9f)) {
          OutlinedTextField(
            value = remainingCountText,
            onValueChange = { remainingCountText = it.filter { ch -> ch.isDigit() }; errorText = null },
            label = { Text("Payments left") },
            placeholder = placeholderIfScan,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          if (scanDraft != null) {
            val displayRemCountField = effectiveScanField(scanDraft.remainingInstallmentsCount, remainingCountText, initialRemainingCountText)
            ScanFieldCaption(
              field = displayRemCountField,
              currentText = remainingCountText,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Remaining installments",
                  field = displayRemCountField,
                  currentDisplay = remainingCountText.ifBlank { null },
                  formatAlt = { it.toString() },
                  onApplyAlt = { alt ->
                    remainingCountText = alt.toString()
                    errorText = null
                  }
                )
              }
            )
          }
        }
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
            onClick = { frequency = freq; errorText = null },
            label = { Text(freq.label) }
          )
        }
      }
      if (scanDraft != null) {
        ScanFieldCaption(
          field = scanDraft.frequency,
          currentText = if (frequency == RepaymentFrequency.UNKNOWN) "" else frequency.label,
          onExplain = {
            explanationTarget = buildExplanationTarget(
              title = "Repayment frequency",
              field = scanDraft.frequency,
              currentDisplay = frequency.label,
              formatAlt = { it.label },
              onApplyAlt = { alt -> frequency = alt; errorText = null }
            )
          }
        )
      }
      if (frequency == RepaymentFrequency.CUSTOM_DAYS) {
        OutlinedTextField(
          value = customDaysText,
          onValueChange = { customDaysText = it.filter { ch -> ch.isDigit() }; errorText = null },
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
          errorText = null
          if (picked == null && dateStatus == DueDateStatus.CONFIRMED) {
            dateStatus = DueDateStatus.UNCONFIRMED
          }
        },
        allowClear = true
      )
      if (scanDraft != null) {
        ScanFieldCaption(
          field = scanDraft.nextDueDateIso,
          currentText = nextDueDate.orEmpty(),
          onExplain = {
            explanationTarget = buildExplanationTarget(
              title = "Next payment date",
              field = scanDraft.nextDueDateIso,
              currentDisplay = nextDueDate?.let { ManilaTime.formatDisplay(it) },
              formatAlt = { ManilaTime.formatDisplay(it) },
              onApplyAlt = { alt ->
                nextDueDate = alt
                errorText = null
              }
            )
          }
        )
      }

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
            onClick = {
              dateStatus = st
              errorText = null
            },
            label = { Text(st.label) }
          )
        }
      }

      if (isScanReview) {
        if (scanDraft?.isSchedulePartial == true && editableScheduleRows.isNotEmpty()) {
          InfoCallout(
            icon = Icons.Outlined.Info,
            title = "Partial schedule view",
            body = "Showing ${editableScheduleRows.size} visible row(s) from the screenshot without extrapolating unseen rows.",
            tone = BadgeTone.NEUTRAL
          )
        }
        ExpandableSection(
          title = "Payment schedule (${editableScheduleRows.size})",
          subtitle = if (editableScheduleRows.isEmpty()) {
            "No schedule rows detected — tap to add dates manually"
          } else {
            "Extracted dates in chronological order · Paid vs Upcoming preserved"
          },
          initiallyExpanded = editableScheduleRows.isNotEmpty()
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editableScheduleRows.isEmpty()) {
              Text(
                text = "No schedule rows extracted from the screenshots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            } else {
              editableScheduleRows.forEachIndexed { idx, rowState ->
                ScheduleRowEditorCard(
                  index = idx,
                  rowState = rowState,
                  onPickDate = { editingScheduleDateRowId = rowState.id },
                  onAmountChange = { newText ->
                    editableScheduleRows[idx] = rowState.copy(amountText = newText)
                    errorText = null
                  },
                  onStatusChange = { newStatus ->
                    editableScheduleRows[idx] = rowState.copy(status = newStatus)
                  },
                  onRemove = { editableScheduleRows.removeAt(idx) }
                )
              }
            }
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              OutlinedButton(
                onClick = {
                  editableScheduleRows.add(
                    EditableScheduleRowState(
                      id = "manual_${System.nanoTime()}",
                      installmentNumberHint = editableScheduleRows.size + 1,
                      dueDateIso = null,
                      rawDateText = "",
                      dateStatus = DueDateStatus.UNCONFIRMED,
                      dateConfidence = ExtractionConfidence.HIGH,
                      amountText = installmentAmountText,
                      status = ExtractedInstallmentStatus.UPCOMING,
                      rawStatusLabel = null,
                      sourceSnippet = "Added during review",
                      screenshotIndex = -1,
                      verificationNote = null
                    )
                  )
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add payment row")
              }
              if (editableScheduleRows.isNotEmpty()) {
                FilledTonalButton(
                  onClick = {
                    val unpaid = editableScheduleRows.filter { it.status.isUnpaidObligation }
                    val paid = editableScheduleRows.filter { it.status.isSettledPaid }
                    remainingCountText = unpaid.size.toString()
                    if (paid.isNotEmpty()) {
                      paidCountText = paid.size.toString()
                      val paidSum = MoneyFormatter.sumCentsExact(paid.mapNotNull { MoneyFormatter.parsePesoToCents(it.amountText) })
                      if (paidSum > 0L) {
                        priorPaidText = MoneyFormatter.formatEditableAmount(paidSum)
                      }
                    }
                    val firstUnpaidDate = unpaid
                      .mapNotNull { it.dueDateIso }
                      .sorted()
                      .firstOrNull()
                    if (firstUnpaidDate != null) {
                      nextDueDate = firstUnpaidDate
                    }
                    errorText = null
                  },
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Sync count & due", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }
        if (scanDraft?.textCorrections?.isNotEmpty() == true) {
          ExpandableSection(
            title = "OCR text cleanup (${scanDraft.textCorrections.size})",
            subtitle = "Raw OCR text preserved alongside normalized symbols",
            initiallyExpanded = false
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              scanDraft.textCorrections.forEach { audit ->
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      text = "Raw: \"${audit.rawText}\" → \"${audit.normalizedText}\"",
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.SemiBold,
                      color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                      text = audit.reason,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              }
            }
          }
        }
      }

      ExpandableSection(
        title = "Loan details (optional)",
        subtitle = "Original principal, fees, prior paid, category & notes",
        initiallyExpanded = autoExpandDetails
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = category,
            onValueChange = { category = it },
            label = { Text("Category") },
            placeholder = placeholderIfScan,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          if (scanDraft != null) {
            ScanFieldCaption(
              field = scanDraft.loanType,
              currentText = category,
              showNotDetectedCaption = false,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Loan category / type",
                  field = scanDraft.loanType,
                  currentDisplay = category.ifBlank { null },
                  formatAlt = { it },
                  onApplyAlt = { alt -> category = alt }
                )
              }
            )
          }
          if (isScanReview) {
            OutlinedTextField(
              value = loanReferenceId,
              onValueChange = { loanReferenceId = it },
              label = { Text("Reference / Order ID") },
              placeholder = placeholderIfScan,
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            )
            ScanFieldCaption(
              field = scanDraft.loanReferenceId,
              currentText = loanReferenceId,
              showNotDetectedCaption = false,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Reference / Order ID",
                  field = scanDraft.loanReferenceId,
                  currentDisplay = loanReferenceId.ifBlank { null },
                  formatAlt = { it },
                  onApplyAlt = { alt -> loanReferenceId = alt }
                )
              }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                  value = originalCountText,
                  onValueChange = { originalCountText = it.filter { ch -> ch.isDigit() }; errorText = null },
                  label = { Text("Orig. count") },
                  placeholder = placeholderIfScan,
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier.fillMaxWidth()
                )
                ScanFieldCaption(
                  field = scanDraft.originalInstallmentCount,
                  currentText = originalCountText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Total installments",
                      field = scanDraft.originalInstallmentCount,
                      currentDisplay = originalCountText.ifBlank { null },
                      formatAlt = { it.toString() },
                      onApplyAlt = { alt -> originalCountText = alt.toString(); errorText = null }
                    )
                  }
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                  value = paidCountText,
                  onValueChange = { paidCountText = it.filter { ch -> ch.isDigit() }; errorText = null },
                  label = { Text("Already paid #") },
                  placeholder = placeholderIfScan,
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier.fillMaxWidth()
                )
                ScanFieldCaption(
                  field = scanDraft.paidInstallmentsCount,
                  currentText = paidCountText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Paid installments",
                      field = scanDraft.paidInstallmentsCount,
                      currentDisplay = paidCountText.ifBlank { null },
                      formatAlt = { it.toString() },
                      onApplyAlt = { alt -> paidCountText = alt.toString(); errorText = null }
                    )
                  }
                )
              }
            }
            DatePickerField(
              label = "Final payment date (optional)",
              dateIso = finalDueDate,
              onDateChanged = { finalDueDate = it },
              allowClear = true
            )
            ScanFieldCaption(
              field = scanDraft.finalDueDateIso,
              currentText = finalDueDate.orEmpty(),
              showNotDetectedCaption = false,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Final payment date",
                  field = scanDraft.finalDueDateIso,
                  currentDisplay = finalDueDate?.let { ManilaTime.formatDisplay(it) },
                  formatAlt = { ManilaTime.formatDisplay(it) },
                  onApplyAlt = { alt -> finalDueDate = alt }
                )
              }
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
              OutlinedTextField(
                value = originalPrincipalText,
                onValueChange = { originalPrincipalText = it; errorText = null },
                label = { Text("Orig. principal") },
                placeholder = placeholderIfScan,
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              )
              if (scanDraft != null) {
                ScanFieldCaption(
                  field = scanDraft.originalPrincipalCents,
                  currentText = originalPrincipalText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Original loan principal",
                      field = scanDraft.originalPrincipalCents,
                      currentDisplay = parsedOrigPrincipal?.let { MoneyFormatter.formatPeso(it) },
                      formatAlt = { MoneyFormatter.formatPeso(it) },
                      onApplyAlt = { alt -> originalPrincipalText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                    )
                  }
                )
              }
            }
            Column(modifier = Modifier.weight(1f)) {
              OutlinedTextField(
                value = amountReceivedText,
                onValueChange = { amountReceivedText = it; errorText = null },
                label = { Text("Received") },
                placeholder = placeholderIfScan,
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              )
              if (scanDraft != null) {
                ScanFieldCaption(
                  field = scanDraft.amountReceivedCents,
                  currentText = amountReceivedText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Amount received",
                      field = scanDraft.amountReceivedCents,
                      currentDisplay = parsedAmountReceived?.let { MoneyFormatter.formatPeso(it) },
                      formatAlt = { MoneyFormatter.formatPeso(it) },
                      onApplyAlt = { alt -> amountReceivedText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                    )
                  }
                )
              }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
              OutlinedTextField(
                value = interestText,
                onValueChange = { interestText = it; errorText = null },
                label = { Text("Interest") },
                placeholder = placeholderIfScan,
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              )
              if (scanDraft != null) {
                ScanFieldCaption(
                  field = scanDraft.interestCents,
                  currentText = interestText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Interest",
                      field = scanDraft.interestCents,
                      currentDisplay = parsedInterest?.let { MoneyFormatter.formatPeso(it) },
                      formatAlt = { MoneyFormatter.formatPeso(it) },
                      onApplyAlt = { alt -> interestText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                    )
                  }
                )
              }
            }
            Column(modifier = Modifier.weight(1f)) {
              OutlinedTextField(
                value = serviceFeeText,
                onValueChange = { serviceFeeText = it; errorText = null },
                label = { Text("Service fee") },
                placeholder = placeholderIfScan,
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              )
              if (scanDraft != null) {
                ScanFieldCaption(
                  field = scanDraft.serviceFeeCents,
                  currentText = serviceFeeText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Service fee",
                      field = scanDraft.serviceFeeCents,
                      currentDisplay = parsedServiceFee?.let { MoneyFormatter.formatPeso(it) },
                      formatAlt = { MoneyFormatter.formatPeso(it) },
                      onApplyAlt = { alt -> serviceFeeText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                    )
                  }
                )
              }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
              OutlinedTextField(
                value = processingFeeText,
                onValueChange = { processingFeeText = it; errorText = null },
                label = { Text("Processing fee") },
                placeholder = placeholderIfScan,
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
              )
              if (scanDraft != null) {
                ScanFieldCaption(
                  field = scanDraft.processingFeeCents,
                  currentText = processingFeeText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Processing fee",
                      field = scanDraft.processingFeeCents,
                      currentDisplay = parsedProcessingFee?.let { MoneyFormatter.formatPeso(it) },
                      formatAlt = { MoneyFormatter.formatPeso(it) },
                      onApplyAlt = { alt -> processingFeeText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                    )
                  }
                )
              }
            }
            if (isScanReview) {
              Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                  value = otherFeesText,
                  onValueChange = { otherFeesText = it; errorText = null },
                  label = { Text("Other fees") },
                  placeholder = placeholderIfScan,
                  prefix = { Text("₱") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                  singleLine = true,
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier.fillMaxWidth()
                )
                ScanFieldCaption(
                  field = scanDraft.otherFeesCents,
                  currentText = otherFeesText,
                  showNotDetectedCaption = false,
                  onExplain = {
                    explanationTarget = buildExplanationTarget(
                      title = "Other fees",
                      field = scanDraft.otherFeesCents,
                      currentDisplay = parsedOtherFees?.let { MoneyFormatter.formatPeso(it) },
                      formatAlt = { MoneyFormatter.formatPeso(it) },
                      onApplyAlt = { alt -> otherFeesText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                    )
                  }
                )
              }
            } else {
              OutlinedTextField(
                value = totalScheduledText,
                onValueChange = { totalScheduledText = it; errorText = null },
                label = { Text("Total scheduled") },
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
              )
            }
          }
          if (isScanReview) {
            OutlinedTextField(
              value = totalScheduledText,
              onValueChange = { totalScheduledText = it; errorText = null },
              label = { Text("Total scheduled repayment") },
              placeholder = placeholderIfScan,
              prefix = { Text("₱") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            )
            ScanFieldCaption(
              field = scanDraft.totalRepaymentCents,
              currentText = totalScheduledText,
              showNotDetectedCaption = false,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Total scheduled repayment",
                  field = scanDraft.totalRepaymentCents,
                  currentDisplay = parsedTotalScheduled?.let { MoneyFormatter.formatPeso(it) },
                  formatAlt = { MoneyFormatter.formatPeso(it) },
                  onApplyAlt = { alt -> totalScheduledText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                )
              }
            )
          }
          OutlinedTextField(
            value = priorPaidText,
            onValueChange = { priorPaidText = it; errorText = null },
            label = { Text("Already paid before adding") },
            placeholder = placeholderIfScan,
            prefix = { Text("₱") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
          if (scanDraft != null) {
            ScanFieldCaption(
              field = scanDraft.calculatedPaidScheduleSumCents,
              currentText = priorPaidText,
              showNotDetectedCaption = false,
              onExplain = {
                explanationTarget = buildExplanationTarget(
                  title = "Already paid before adding",
                  field = scanDraft.calculatedPaidScheduleSumCents,
                  currentDisplay = MoneyFormatter.parsePesoToCents(priorPaidText)?.let { MoneyFormatter.formatPeso(it) },
                  formatAlt = { MoneyFormatter.formatPeso(it) },
                  onApplyAlt = { alt -> priorPaidText = MoneyFormatter.formatEditableAmount(alt); errorText = null }
                )
              }
            )
          }
          OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          )
        }
      }

      if (isScanReview) {
        val warnings = liveIssues.filter { it.severity == ImportSeverity.WARNING }
        if (warnings.isNotEmpty()) {
          InfoCallout(
            icon = Icons.Outlined.WarningAmber,
            title = "${warnings.size} consistency check(s)",
            body = warnings.joinToString("\n") { "• ${it.title}: ${it.message}" },
            tone = BadgeTone.WARNING
          )
        }

        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { attachScreenshots = !attachScreenshots }
              .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
              Text(
                text = "Keep screenshots attached to this debt record",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Off by default. Temporary scan copies are deleted automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Switch(
              checked = attachScreenshots,
              onCheckedChange = { attachScreenshots = it }
            )
          }
        }
      }

      if (errorText != null) {
        InfoCallout(
          icon = Icons.Outlined.ErrorOutline,
          title = "Check before saving",
          body = errorText!!,
          tone = BadgeTone.DANGER
        )
      }

      SheetButtonRow(
        confirmText = if (isSaving) "Saving…" else "Save debt",
        onCancel = { if (!isSaving) onDismiss() },
        onConfirm = {
          if (isSaving) return@SheetButtonRow
          fun parseOptionalPeso(raw: String): Pair<Boolean, Long?> {
            if (raw.isBlank()) return true to null
            val parsed = MoneyFormatter.parsePesoToCents(raw)
            return (parsed != null && parsed >= 0L) to parsed
          }

          val remBalance = MoneyFormatter.parsePesoToCents(remainingBalanceText)
          if (name.isBlank()) {
            errorText = "Debt name is required"
            return@SheetButtonRow
          }
          if (remBalance == null || remBalance < 0L) {
            errorText = "Enter a valid remaining balance"
            return@SheetButtonRow
          }
          val (instOk, instParsed) = parseOptionalPeso(installmentAmountText)
          val (origOk, origPrincipal) = parseOptionalPeso(originalPrincipalText)
          val (disbOk, disbursedAmount) = parseOptionalPeso(disbursedAmountText)
          val (recvOk, amountReceived) = parseOptionalPeso(amountReceivedText)
          val (intOk, interestCents) = parseOptionalPeso(interestText)
          val (svcOk, serviceFeeCents) = parseOptionalPeso(serviceFeeText)
          val (procOk, processingFeeCents) = parseOptionalPeso(processingFeeText)
          val (otherOk, otherFeesCents) = parseOptionalPeso(otherFeesText)
          val (pastDueOk, pastDueAmount) = parseOptionalPeso(pastDueAmountText)
          val (chargesOk, unpaidCharges) = parseOptionalPeso(unpaidChargesText)
          val (limitOk, creditLimitCents) = parseOptionalPeso(creditLimitText)
          val (availOk, availableCreditCents) = parseOptionalPeso(availableCreditText)
          val (usedOk, usedCreditCents) = parseOptionalPeso(usedCreditText)
          val (stmtOk, statementBalanceCents) = parseOptionalPeso(statementBalanceText)
          val (minDueOk, minimumDueCents) = parseOptionalPeso(minimumDueText)
          val (schedOk, totalScheduled) = parseOptionalPeso(totalScheduledText)
          val (priorOk, priorPaidParsed) = parseOptionalPeso(priorPaidText)
          if (!instOk || !origOk || !disbOk || !recvOk || !intOk || !svcOk || !procOk || !otherOk ||
            !pastDueOk || !chargesOk || !limitOk || !availOk || !usedOk || !stmtOk || !minDueOk || !schedOk || !priorOk
          ) {
            errorText = "Check peso amounts — enter numbers only or leave optional fields blank"
            return@SheetButtonRow
          }
          val customDays = if (frequency == RepaymentFrequency.CUSTOM_DAYS) {
            customDaysText.toIntOrNull()?.takeIf { it > 0 }
          } else null
          if (frequency == RepaymentFrequency.CUSTOM_DAYS && customDays == null) {
            errorText = "Enter custom interval days (at least 1)"
            return@SheetButtonRow
          }
          if (remBalance > 0L && dateStatus == DueDateStatus.CONFIRMED && nextDueDate == null) {
            errorText = "Select the confirmed next due date first"
            return@SheetButtonRow
          }

          if (isScanReview) {
            editableScheduleRows.forEachIndexed { idx, r ->
              if (r.amountText.isNotBlank()) {
                val rowAmt = MoneyFormatter.parsePesoToCents(r.amountText)
                if (rowAmt == null || rowAmt <= 0L) {
                  errorText = "Schedule row #${idx + 1} needs a positive peso amount or blank"
                  return@SheetButtonRow
                }
              }
            }
            val blockingError = liveIssues.firstOrNull { it.severity == ImportSeverity.ERROR }
            if (blockingError != null) {
              errorText = "${blockingError.title}: ${blockingError.message}"
              return@SheetButtonRow
            }
            if (liveDuplicates.isNotEmpty() && !allowDuplicateOverride) {
              errorText = "Confirm \"This is a separate loan\" above duplicate warning, or cancel."
              return@SheetButtonRow
            }
          }

          val instAmt = instParsed ?: 0L
          val remCount = if (remainingCountText.isNotBlank()) {
            val parsedCount = remainingCountText.toIntOrNull()
            if (parsedCount == null || parsedCount < 0) {
              errorText = "Enter a valid number of payments left"
              return@SheetButtonRow
            }
            parsedCount
          } else {
            val unpaidCountFromRows = editableScheduleRows.count { it.status != ExtractedInstallmentStatus.PAID }
            when {
              remBalance == 0L -> 0
              unpaidCountFromRows > 0 -> unpaidCountFromRows
              instAmt > 0L -> 1
              else -> 0
            }
          }
          val resolvedStatus = when {
            existing?.status == DebtStatus.ARCHIVED -> DebtStatus.ARCHIVED
            remBalance == 0L -> DebtStatus.PAID
            else -> DebtStatus.ACTIVE
          }

          val combinedNotes = if (isScanReview) {
            buildScannedNotes(
              userNotes = notes,
              loanReferenceId = loanReferenceId,
              otherFeesCents = otherFeesCents,
              origCount = parsedOrigCount,
              paidCount = parsedPaidCount,
              finalDueDateIso = finalDueDate
            )
          } else {
            notes.trim()
          }

          val resolvedObligationRole = when {
            linkedParentDebtId != null && productClassification == LoanProductClassification.CREDIT_BILL_INSTALLMENT ->
              ObligationRole.CONVERTED_INSTALLMENT
            linkedParentDebtId != null -> ObligationRole.BILLING_STATEMENT
            else -> ObligationRole.STANDALONE
          }

          val entity = (existing ?: DebtEntity(
            name = name.trim(),
            lender = lender.ifBlank { "Manual Entry" },
            category = category.ifBlank { "Loan" },
            remainingBalanceCents = remBalance
          )).copy(
            name = name.trim(),
            lender = lender.ifBlank { "Manual Entry" },
            category = category.ifBlank { "Loan" },
            productClassification = productClassification,
            applicationProvider = applicationProvider.trim().ifBlank { null },
            actualCreditor = actualCreditor.trim().ifBlank { null },
            loanReferenceId = loanReferenceId.trim().ifBlank { null },
            loanBookingDateIso = loanBookingDateIso,
            originalPrincipalCents = origPrincipal,
            disbursedAmountCents = disbursedAmount,
            amountReceivedCents = amountReceived,
            interestCents = interestCents,
            serviceFeeCents = serviceFeeCents,
            processingFeeCents = processingFeeCents,
            otherChargesCents = otherFeesCents,
            pastDueAmountCents = pastDueAmount,
            unpaidChargesCents = unpaidCharges,
            creditLimitCents = creditLimitCents,
            availableCreditCents = availableCreditCents,
            usedCreditCents = usedCreditCents,
            statementBalanceCents = statementBalanceCents,
            minimumDueCents = minimumDueCents,
            statementDateIso = statementDateIso,
            statementPeriod = statementPeriod.trim().ifBlank { null },
            totalScheduledRepaymentCents = totalScheduled,
            priorPaidCents = priorPaidParsed ?: 0L,
            isPriorPaidKnown = priorPaidParsed != null,
            remainingBalanceCents = remBalance,
            installmentAmountCents = instAmt,
            originalInstallmentsCount = parsedOrigCount,
            paidInstallmentsCount = parsedPaidCount,
            remainingInstallmentsCount = remCount,
            frequency = frequency,
            customIntervalDays = customDays,
            lastKnownPaidDate = existing?.lastKnownPaidDate
              ?: scanDraft?.lastPaidDateIso?.value
              ?: editableScheduleRows
                .filter { it.status.isSettledPaid }
                .mapNotNull { it.dueDateIso }
                .maxOrNull(),
            nextDueDate = nextDueDate,
            dateStatus = dateStatus,
            status = resolvedStatus,
            scheduleVersion = scanDraft?.scheduleVersion ?: existing?.scheduleVersion ?: 1,
            scheduleEffectiveDateIso = scanDraft?.scheduleEffectiveDateIso ?: existing?.scheduleEffectiveDateIso,
            obligationRole = resolvedObligationRole,
            parentDebtId = linkedParentDebtId,
            isManuallyEntered = true,
            notes = combinedNotes
          )

          if (isScanReview && onSaveImported != null) {
            val scheduleEntities = editableScheduleRows.mapIndexed { idx, rowState ->
              val isPaidRow = rowState.status.isSettledPaid
              val rowAmt = MoneyFormatter.parsePesoToCents(rowState.amountText)?.coerceAtLeast(0L) ?: 0L
              val rowDateStatus = when {
                rowState.dueDateIso.isNullOrBlank() -> DueDateStatus.UNKNOWN
                dateStatus == DueDateStatus.CONFIRMED -> DueDateStatus.CONFIRMED
                else -> rowState.dateStatus
              }
              val mappedStatus = when (rowState.status) {
                ExtractedInstallmentStatus.PAID -> InstallmentStatus.PAID
                ExtractedInstallmentStatus.PARTIALLY_PAID -> InstallmentStatus.PARTIAL
                ExtractedInstallmentStatus.OVERDUE -> InstallmentStatus.OVERDUE
                ExtractedInstallmentStatus.PENDING -> InstallmentStatus.PENDING
                ExtractedInstallmentStatus.UNPAID -> InstallmentStatus.UNPAID
                ExtractedInstallmentStatus.REFUNDED -> InstallmentStatus.REFUNDED
                ExtractedInstallmentStatus.REVERSED -> InstallmentStatus.REVERSED
                ExtractedInstallmentStatus.DISPUTED -> InstallmentStatus.DISPUTED
                ExtractedInstallmentStatus.UNKNOWN -> InstallmentStatus.UNKNOWN
                ExtractedInstallmentStatus.UPCOMING -> InstallmentStatus.UPCOMING
              }
              InstallmentEntity(
                debtId = 0L,
                installmentNumber = rowState.installmentNumberHint ?: (idx + 1),
                dueDate = rowState.dueDateIso,
                amountDueCents = rowAmt,
                paidAmountCents = if (isPaidRow) rowAmt else 0L,
                dateStatus = rowDateStatus,
                status = mappedStatus,
                paidDate = if (isPaidRow) rowState.dueDateIso else null,
                scheduleVersion = scanDraft?.scheduleVersion ?: 1,
                rawStatusLabel = rowState.rawStatusLabel,
                isManuallyEntered = true,
                notes = if (rowState.status == ExtractedInstallmentStatus.OVERDUE) {
                  "Imported as overdue"
                } else if (rowState.screenshotIndex >= 0) {
                  "Scanned from #${rowState.screenshotIndex + 1}"
                } else {
                  "Added during scan review"
                }
              )
            }
            onSaveImported(entity, scheduleEntities, attachScreenshots)
          } else {
            onSave(entity)
            onDismiss()
          }
        }
      )
    }
  }

  editingScheduleDateRowId?.let { targetRowId ->
    val targetIdx = editableScheduleRows.indexOfFirst { it.id == targetRowId }
    if (targetIdx >= 0) {
      BaonDatePickerDialog(
        initialDateIso = editableScheduleRows[targetIdx].dueDateIso,
        onDateSelected = { pickedIso ->
          editableScheduleRows[targetIdx] = editableScheduleRows[targetIdx].copy(
            dueDateIso = pickedIso,
            dateConfidence = ExtractionConfidence.HIGH,
            dateStatus = if (dateStatus == DueDateStatus.CONFIRMED) DueDateStatus.CONFIRMED else DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
          )
          editingScheduleDateRowId = null
        },
        onDismiss = { editingScheduleDateRowId = null }
      )
    }
  }

  explanationTarget?.let { target ->
    FieldExplanationDialog(
      target = target,
      onDismiss = { explanationTarget = null }
    )
  }
}

private data class ExplanationAlternativeUi(
  val label: String,
  val sourceNote: String?,
  val onApply: () -> Unit
)

private data class ExplanationSheetTarget(
  val title: String,
  val currentDisplay: String?,
  val origin: FieldOrigin,
  val confidence: ExtractionConfidence,
  val hasConflict: Boolean,
  val reason: String,
  val sourceSnippet: String?,
  val sourceScreenshotIndex: Int?,
  val alternatives: List<ExplanationAlternativeUi>
)

private fun <T> effectiveScanField(
  field: ExtractedField<T>,
  currentText: String,
  initialText: String
): ExtractedField<T> =
  if (currentText.trim() != initialText.trim() && currentText.isNotBlank()) {
    field.copy(
      origin = FieldOrigin.MANUALLY_CORRECTED,
      verificationReason = "Edited by you during review."
    )
  } else {
    field
  }

private fun <T> buildExplanationTarget(
  title: String,
  field: ExtractedField<T>,
  currentDisplay: String?,
  formatAlt: (T) -> String,
  onApplyAlt: (T) -> Unit
): ExplanationSheetTarget {
  val defaultReason = when (field.origin) {
    FieldOrigin.EXTRACTED -> "Directly read from the uploaded screenshot text."
    FieldOrigin.CALCULATED -> "Calculated mathematically from other extracted values."
    FieldOrigin.ESTIMATED -> "Estimated from surrounding dates, payment rows, or patterns."
    FieldOrigin.MANUALLY_CORRECTED -> "Edited by you during review."
    FieldOrigin.NEEDS_REVIEW -> "Detected with uncertainty or conflict — verify before saving."
    FieldOrigin.NOT_DETECTED -> "Not clearly found in the uploaded screenshots."
  }
  val alts = field.alternatives.map { alt ->
    val altLabel = alt.label.ifBlank { formatAlt(alt.value) }
    val altNote = buildString {
      append(alt.origin.badgeLabel)
      if (alt.screenshotIndex != null) append(" · Screenshot #${alt.screenshotIndex + 1}")
      if (!alt.sourceSnippet.isNullOrBlank()) append(" · \"${alt.sourceSnippet.take(48)}\"")
    }.ifBlank { null }
    ExplanationAlternativeUi(
      label = altLabel,
      sourceNote = altNote,
      onApply = { onApplyAlt(alt.value) }
    )
  }
  return ExplanationSheetTarget(
    title = title,
    currentDisplay = currentDisplay,
    origin = field.origin,
    confidence = field.confidence,
    hasConflict = field.hasConflict,
    reason = field.verificationReason?.takeIf { it.isNotBlank() } ?: defaultReason,
    sourceSnippet = field.sourceSnippet,
    sourceScreenshotIndex = field.sourceScreenshotIndex,
    alternatives = alts
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldExplanationDialog(
  target: ExplanationSheetTarget,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(target.title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          val originTone = when (target.origin) {
            FieldOrigin.EXTRACTED -> BadgeTone.NEUTRAL
            FieldOrigin.CALCULATED -> BadgeTone.PRIMARY
            FieldOrigin.ESTIMATED -> BadgeTone.WARNING
            FieldOrigin.MANUALLY_CORRECTED -> BadgeTone.POSITIVE
            FieldOrigin.NEEDS_REVIEW -> BadgeTone.DANGER
            FieldOrigin.NOT_DETECTED -> BadgeTone.NEUTRAL
          }
          StatusBadge(text = target.origin.badgeLabel, tone = originTone)

          if (target.origin != FieldOrigin.NOT_DETECTED) {
            if (target.hasConflict) {
              StatusBadge(text = "Conflicting values — verify", tone = BadgeTone.DANGER)
            } else {
              val confTone = when (target.confidence) {
                ExtractionConfidence.HIGH -> BadgeTone.POSITIVE
                ExtractionConfidence.MEDIUM -> BadgeTone.WARNING
                ExtractionConfidence.LOW -> BadgeTone.DANGER
              }
              StatusBadge(text = target.confidence.userLabel, tone = confTone)
            }
          }
        }

        if (!target.currentDisplay.isNullOrBlank()) {
          Text(
            text = "Current value: ${target.currentDisplay}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
          )
        }

        Text(
          text = target.reason,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!target.sourceSnippet.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, LocalBaonColors.current.divider),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
              val header = target.sourceScreenshotIndex?.let { "Source text · Screenshot #${it + 1}" }
                ?: "Supporting evidence"
              Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = "\"${target.sourceSnippet}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }

        if (target.alternatives.isNotEmpty()) {
          Text(
            text = "Other detected options (tap to use)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            target.alternatives.forEach { alt ->
              OutlinedButton(
                onClick = {
                  alt.onApply()
                  onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                  Text(
                    text = alt.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                  )
                  if (!alt.sourceNote.isNullOrBlank()) {
                    Text(
                      text = alt.sourceNote,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Got it")
      }
    }
  )
}

private data class EditableScheduleRowState(
  val id: String,
  val installmentNumberHint: Int?,
  val dueDateIso: String?,
  val rawDateText: String,
  val dateStatus: DueDateStatus,
  val dateConfidence: ExtractionConfidence,
  val amountText: String,
  val status: ExtractedInstallmentStatus,
  val rawStatusLabel: String? = null,
  val sourceSnippet: String,
  val screenshotIndex: Int,
  val verificationNote: String?
) {
  fun toExtractedRow(): ExtractedInstallmentRow = ExtractedInstallmentRow(
    id = id,
    installmentNumberHint = installmentNumberHint,
    dueDateIso = dueDateIso,
    rawDateText = rawDateText,
    dateStatus = dateStatus,
    dateConfidence = dateConfidence,
    amountCents = MoneyFormatter.parsePesoToCents(amountText),
    status = status,
    sourceSnippet = sourceSnippet,
    screenshotIndex = screenshotIndex,
    verificationNote = verificationNote
  )

  companion object {
    fun fromExtracted(row: ExtractedInstallmentRow): EditableScheduleRowState = EditableScheduleRowState(
      id = row.id,
      installmentNumberHint = row.installmentNumberHint,
      dueDateIso = row.dueDateIso,
      rawDateText = row.rawDateText,
      dateStatus = row.dateStatus,
      dateConfidence = row.dateConfidence,
      amountText = MoneyFormatter.formatEditableAmount(row.amountCents),
      status = row.status,
      rawStatusLabel = row.status.takeIf { it != ExtractedInstallmentStatus.UNKNOWN }?.label,
      sourceSnippet = row.sourceSnippet,
      screenshotIndex = row.screenshotIndex,
      verificationNote = row.verificationNote
    )
  }
}

@Composable
private fun <T> ScanFieldCaption(
  field: ExtractedField<T>,
  currentText: String,
  showNotDetectedCaption: Boolean = true,
  onExplain: (() -> Unit)? = null
) {
  val semantic = LocalBaonColors.current
  if (currentText.isBlank() && field.origin == FieldOrigin.NOT_DETECTED) {
    if (!showNotDetectedCaption) return
    val reasonPreview = field.verificationReason?.takeIf { it.isNotBlank() }
    val captionText = if (reasonPreview != null) {
      "Not detected · $reasonPreview"
    } else {
      "Not detected"
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .then(if (onExplain != null && reasonPreview != null) Modifier.clickable(onClick = onExplain) else Modifier)
        .padding(horizontal = 4.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = captionText,
        style = MaterialTheme.typography.labelSmall,
        color = semantic.muted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (onExplain != null && reasonPreview != null) {
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "Why?",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
    return
  }

  if (field.origin == FieldOrigin.NOT_DETECTED) return

  val needsVerify = field.hasConflict ||
    field.confidence == ExtractionConfidence.LOW ||
    field.origin == FieldOrigin.NEEDS_REVIEW
  val highlightReview = !needsVerify && field.confidence == ExtractionConfidence.MEDIUM
  val originTag = when (field.origin) {
    FieldOrigin.CALCULATED -> "Calculated"
    FieldOrigin.ESTIMATED -> "Estimated"
    FieldOrigin.MANUALLY_CORRECTED -> "Edited"
    FieldOrigin.NEEDS_REVIEW -> "Needs review"
    FieldOrigin.EXTRACTED -> field.sourceScreenshotIndex?.let { "From #${it + 1}" } ?: "Extracted"
    FieldOrigin.NOT_DETECTED -> "Not detected"
  }
  val statusTag = when {
    field.hasConflict -> " · Conflicting values"
    field.confidence == ExtractionConfidence.LOW -> " · Verify required"
    field.confidence == ExtractionConfidence.MEDIUM -> " · Check value"
    else -> ""
  }
  val snippetOrReason = field.verificationReason
    ?: field.sourceSnippet?.take(44)?.let { "\"$it\"" }
  val summaryText = "$originTag$statusTag${snippetOrReason?.let { " · $it" } ?: ""}"

  val (containerBg, textColor) = when {
    needsVerify -> semantic.dangerContainer.copy(alpha = 0.72f) to semantic.onDangerContainer
    highlightReview -> semantic.warningContainer.copy(alpha = 0.72f) to semantic.onWarningContainer
    field.origin == FieldOrigin.CALCULATED ->
      androidx.compose.ui.graphics.Color.Transparent to MaterialTheme.colorScheme.primary
    else -> androidx.compose.ui.graphics.Color.Transparent to semantic.muted
  }

  val clickableMod = if (onExplain != null) Modifier.clickable(onClick = onExplain) else Modifier
  if (needsVerify || highlightReview) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = containerBg,
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 2.dp)
        .then(clickableMod)
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = summaryText,
          style = MaterialTheme.typography.labelSmall,
          color = textColor,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        if (onExplain != null) {
          Spacer(modifier = Modifier.width(6.dp))
          Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Why BaonPlan chose this value",
            tint = textColor,
            modifier = Modifier.size(14.dp)
          )
        }
      }
    }
  } else {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .then(clickableMod)
        .padding(horizontal = 4.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = summaryText,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
      if (onExplain != null) {
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "Why?",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
  }
}

@Composable
private fun RevolvingCreditReviewCard(
  creditLimitText: String,
  onCreditLimitChange: (String) -> Unit,
  availableCreditText: String,
  onAvailableCreditChange: (String) -> Unit,
  usedCreditText: String,
  onUsedCreditChange: (String) -> Unit,
  statementBalanceText: String,
  onStatementBalanceChange: (String) -> Unit,
  minimumDueText: String,
  onMinimumDueChange: (String) -> Unit,
  onApplyAsRemainingBalance: (Long) -> Unit
) {
  val semantic = LocalBaonColors.current
  val parsedStatement = MoneyFormatter.parsePesoToCents(statementBalanceText)
  val parsedUsed = MoneyFormatter.parsePesoToCents(usedCreditText)

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.divider),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "Credit line & statement figures",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = creditLimitText,
          onValueChange = onCreditLimitChange,
          label = { Text("Credit limit") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = availableCreditText,
          onValueChange = onAvailableCreditChange,
          label = { Text("Available credit") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f)
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = statementBalanceText,
          onValueChange = onStatementBalanceChange,
          label = { Text("Statement bal.") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = minimumDueText,
          onValueChange = onMinimumDueChange,
          label = { Text("Minimum due") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f)
        )
      }
      OutlinedTextField(
        value = usedCreditText,
        onValueChange = onUsedCreditChange,
        label = { Text("Used / outstanding credit") },
        prefix = { Text("₱") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
      )
      if (parsedStatement != null || parsedUsed != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (parsedStatement != null) {
            FilledTonalButton(
              onClick = { onApplyAsRemainingBalance(parsedStatement) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Use statement bal.", style = MaterialTheme.typography.labelSmall)
            }
          }
          if (parsedUsed != null && parsedUsed != parsedStatement) {
            OutlinedButton(
              onClick = { onApplyAsRemainingBalance(parsedUsed) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Use used credit", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceComparisonReviewCard(
  extractedBalanceCents: Long?,
  extractedAmountCurrentlyDueCents: Long? = null,
  extractedRemainingInstallmentTotalCents: Long?,
  calculatedUnpaidScheduleSumCents: Long?,
  onApplyAmount: (Long) -> Unit
) {
  if (extractedBalanceCents == null &&
    extractedAmountCurrentlyDueCents == null &&
    extractedRemainingInstallmentTotalCents == null &&
    calculatedUnpaidScheduleSumCents == null
  ) {
    return
  }
  val semantic = LocalBaonColors.current
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.divider),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Text(
        text = "Extracted vs. calculated totals",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
      )
      MetricRow(
        label = "Extracted Balance",
        value = extractedBalanceCents?.let { MoneyFormatter.formatPeso(it) } ?: "Not detected",
        boldValue = false
      )
      if (extractedAmountCurrentlyDueCents != null) {
        MetricRow(
          label = "Amount currently due",
          value = MoneyFormatter.formatPeso(extractedAmountCurrentlyDueCents),
          boldValue = false
        )
      }
      if (extractedRemainingInstallmentTotalCents != null) {
        MetricRow(
          label = "Extracted Remaining Inst. Total",
          value = MoneyFormatter.formatPeso(extractedRemainingInstallmentTotalCents),
          boldValue = false
        )
      }
      if (calculatedUnpaidScheduleSumCents != null) {
        MetricRow(
          label = "Calculated by app (unpaid rows)",
          value = MoneyFormatter.formatPeso(calculatedUnpaidScheduleSumCents),
          valueColor = MaterialTheme.colorScheme.primary,
          boldValue = false
        )
      }
      val distinctAmounts = listOfNotNull(
        extractedBalanceCents,
        extractedAmountCurrentlyDueCents,
        extractedRemainingInstallmentTotalCents,
        calculatedUnpaidScheduleSumCents
      ).distinct()
      val hasChoices = distinctAmounts.size > 1 ||
        (extractedBalanceCents == null && distinctAmounts.isNotEmpty())
      if (hasChoices) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          if (extractedBalanceCents != null) {
            FilledTonalButton(
              onClick = { onApplyAmount(extractedBalanceCents) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Use balance", style = MaterialTheme.typography.labelSmall)
            }
          }
          if (extractedAmountCurrentlyDueCents != null) {
            OutlinedButton(
              onClick = { onApplyAmount(extractedAmountCurrentlyDueCents) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Use due now", style = MaterialTheme.typography.labelSmall)
            }
          }
          if (extractedRemainingInstallmentTotalCents != null) {
            FilledTonalButton(
              onClick = { onApplyAmount(extractedRemainingInstallmentTotalCents) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Use inst. total", style = MaterialTheme.typography.labelSmall)
            }
          }
          if (calculatedUnpaidScheduleSumCents != null) {
            OutlinedButton(
              onClick = { onApplyAmount(calculatedUnpaidScheduleSumCents) },
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Use row sum", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleRowEditorCard(
  index: Int,
  rowState: EditableScheduleRowState,
  onPickDate: () -> Unit,
  onAmountChange: (String) -> Unit,
  onStatusChange: (ExtractedInstallmentStatus) -> Unit,
  onRemove: () -> Unit
) {
  val semantic = LocalBaonColors.current
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, semantic.divider),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier.padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Payment #${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
          )
          if (rowState.screenshotIndex >= 0) {
            StatusBadge(text = "From #${rowState.screenshotIndex + 1}", tone = BadgeTone.NEUTRAL)
          }
          if (rowState.dueDateIso == null) {
            StatusBadge(text = "Date missing", tone = BadgeTone.WARNING)
          } else if (rowState.dateConfidence != ExtractionConfidence.HIGH) {
            StatusBadge(text = "Verify", tone = BadgeTone.WARNING)
          }
        }
        IconButton(
          onClick = onRemove,
          modifier = Modifier.size(28.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove payment row #${index + 1}",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedButton(
          onClick = onPickDate,
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
          modifier = Modifier.weight(1f)
        ) {
          Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = rowState.dueDateIso?.let { ManilaTime.formatDisplay(it) } ?: "Set date",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
          )
        }
        OutlinedTextField(
          value = rowState.amountText,
          onValueChange = onAmountChange,
          label = { Text("Amount") },
          placeholder = { Text("Missing") },
          prefix = { Text("₱") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f)
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        listOf(
          ExtractedInstallmentStatus.PAID to "Paid",
          ExtractedInstallmentStatus.UPCOMING to "Upcoming",
          ExtractedInstallmentStatus.OVERDUE to "Overdue",
          ExtractedInstallmentStatus.PENDING to "Pending",
          ExtractedInstallmentStatus.REFUNDED to "Refunded",
          ExtractedInstallmentStatus.UNKNOWN to "Unknown"
        ).forEach { (st, lbl) ->
          FilterChip(
            selected = rowState.status == st,
            onClick = { onStatusChange(st) },
            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }
          )
        }
      }

      if (rowState.verificationNote != null) {
        Text(
          text = rowState.verificationNote,
          style = MaterialTheme.typography.labelSmall,
          color = semantic.warning
        )
      }
    }
  }
}

private fun buildScannedNotes(
  userNotes: String,
  loanReferenceId: String,
  otherFeesCents: Long?,
  origCount: Int?,
  paidCount: Int?,
  finalDueDateIso: String?
): String {
  val parts = mutableListOf<String>()
  val cleanUser = userNotes.trim()
  if (cleanUser.isNotBlank()) parts.add(cleanUser)
  val cleanRef = loanReferenceId.trim()
  if (cleanRef.isNotBlank() && !cleanUser.contains(cleanRef, ignoreCase = true)) {
    parts.add("Ref: #$cleanRef")
  }
  if (otherFeesCents != null && otherFeesCents > 0L) {
    parts.add("Other fees: ${MoneyFormatter.formatPeso(otherFeesCents)}")
  }
  if (origCount != null || paidCount != null) {
    val countLine = when {
      origCount != null && paidCount != null -> "Term: $paidCount of $origCount installments paid before adding"
      origCount != null -> "Original installments: $origCount"
      else -> "Installments paid before adding: $paidCount"
    }
    parts.add(countLine)
  }
  if (!finalDueDateIso.isNullOrBlank()) {
    parts.add("Final payment date: ${ManilaTime.formatDisplay(finalDueDateIso)}")
  }
  return parts.joinToString("\n")
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
      if (debt.frequency == RepaymentFrequency.UNKNOWN ||
        debt.frequency == RepaymentFrequency.IRREGULAR ||
        debt.frequency == RepaymentFrequency.TWICE_MONTHLY ||
        debt.frequency == RepaymentFrequency.ONE_TIME
      ) {
        RepaymentFrequency.WEEKLY
      } else {
        debt.frequency
      }
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
        text = "Creates upcoming payment rows for ${debt.name} using known regular intervals only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        RepaymentFrequency.entries
          .filter { it != RepaymentFrequency.UNKNOWN && it != RepaymentFrequency.IRREGULAR }
          .forEach { freq ->
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
          val customDays = if (frequency == RepaymentFrequency.CUSTOM_DAYS) customDaysText.toIntOrNull() else null
          when {
            ManilaTime.parseDate(startDateIso) == null -> {
              errorText = "Select a valid start date"
            }
            frequency == RepaymentFrequency.CUSTOM_DAYS && (customDays == null || customDays <= 0) -> {
              errorText = "Enter custom interval days (at least 1)"
            }
            count == null || count <= 0 || amt == null || amt <= 0L -> {
              errorText = "Enter valid payment count and peso amount"
            }
            else -> {
              onGenerate(
                startDateIso,
                count,
                amt,
                frequency,
                customDays,
                if (confirmed) DueDateStatus.CONFIRMED else DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
              )
              onDismiss()
            }
          }
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
  val canAutoGenerate = debt.frequency != RepaymentFrequency.UNKNOWN &&
    debt.frequency != RepaymentFrequency.IRREGULAR &&
    debt.remainingInstallmentsCount > 1
  var generateSchedule by remember {
    mutableStateOf(canAutoGenerate)
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
        if (canAutoGenerate) {
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
      val isAdjustment = tx.type == TransactionType.ADJUSTMENT
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Description") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      )
      val amountSupportingText: (@Composable () -> Unit)? = when {
        errorText != null -> {
          { Text(errorText!!) }
        }
        isAdjustment -> {
          { Text("Amount is locked for adjustments — void & revert to undo") }
        }
        else -> null
      }
      OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it; errorText = null },
        label = { Text("Amount") },
        prefix = { Text("₱") },
        enabled = !isAdjustment,
        readOnly = isAdjustment,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = errorText != null,
        supportingText = amountSupportingText,
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
          val cents = if (isAdjustment) tx.amountCents else MoneyFormatter.parsePesoToCents(amountText)
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
