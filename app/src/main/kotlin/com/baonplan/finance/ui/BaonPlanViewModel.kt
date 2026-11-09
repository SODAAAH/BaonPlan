package com.baonplan.finance.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.baonplan.finance.data.backup.BackupSerializer
import com.baonplan.finance.data.backup.BaonPlanBackupPayload
import com.baonplan.finance.data.local.AppPreferencesState
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.data.repository.BaonPlanRepository
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.DuesCoverageStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.PayoffProjectionSummary
import com.baonplan.finance.domain.model.PaymentOverview
import com.baonplan.finance.domain.model.PriorWeekCarryOverCandidate
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.RepaymentProgressSummary
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ScenarioPlan
import com.baonplan.finance.domain.model.ThemeMode
import com.baonplan.finance.domain.model.WeeklyBudgetSummary
import com.baonplan.finance.domain.model.WeeklyDuesReadiness
import com.baonplan.finance.domain.model.WeeklyTrendPoint
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.notifications.NotificationHelper
import com.baonplan.finance.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class BaonPlanUiState(
  val isLoading: Boolean = true,
  val today: LocalDate = ManilaTime.today(),
  val settings: UserSettingsEntity = DefaultSettingsProvider.defaultSettings(),
  val preferences: AppPreferencesState = AppPreferencesState(),
  val debts: List<DebtEntity> = emptyList(),
  val installments: List<InstallmentEntity> = emptyList(),
  val transactions: List<TransactionEntity> = emptyList(),
  val allowances: List<AllowanceRecordEntity> = emptyList(),
  val expenses: List<ExpenseEntity> = emptyList(),
  val reserves: List<DebtReserveEntity> = emptyList(),
  val totalActiveDebtCents: Long = 0L,
  val repaymentProgress: RepaymentProgressSummary = FinancialCalculator.calculateRepaymentProgress(emptyList()),
  val weeklySummary: WeeklyBudgetSummary = FinancialCalculator.calculateWeeklyBudgetSummary(
    today = ManilaTime.today(),
    settings = DefaultSettingsProvider.defaultSettings(),
    allowances = emptyList(),
    expenses = emptyList(),
    reserves = emptyList(),
    transactions = emptyList()
  ),
  val paymentOverview: PaymentOverview = FinancialCalculator.buildPaymentOverview(
    emptyList(),
    emptyList(),
    ManilaTime.today()
  ),
  val scenario400: ScenarioPlan = FinancialCalculator.calculateScenarioPlan(
    scenarioName = "₱400 Conservative Week",
    allowanceCents = 40_000L,
    foodBudgetCents = 10_000L,
    otherExpensesCents = 5_000L,
    plannedDebtReserveCents = 15_000L,
    emergencySavingsCents = 5_000L
  ),
  val scenario500: ScenarioPlan = FinancialCalculator.calculateScenarioPlan(
    scenarioName = "₱500 Full Week",
    allowanceCents = 50_000L,
    foodBudgetCents = 10_000L,
    otherExpensesCents = 5_000L,
    plannedDebtReserveCents = 15_000L,
    emergencySavingsCents = 5_000L
  ),
  val activeDebtReserveTotalCents: Long = 0L,
  val emergencySavingsTotalCents: Long = 0L,
  val duesReadiness: WeeklyDuesReadiness = WeeklyDuesReadiness(
    dueNext7DaysCents = 0L,
    overdueCents = 0L,
    dueNext7DaysCount = 0,
    activeDebtReserveCents = 0L,
    availableCashCents = 0L,
    shortfallAfterReserveCents = 0L,
    unmetShortfallCents = 0L,
    status = DuesCoverageStatus.NO_DUES
  ),
  val payoffInsights: PayoffProjectionSummary = PayoffProjectionSummary(
    includedDebtsCount = 0,
    excludedDebtsCount = 0,
    includedTotalBalanceCents = 0L,
    estimatedDebtFreeDateIso = null,
    estimatedWeeksRemaining = null,
    includesEstimatedDates = false,
    quickestWinDebt = null,
    soonestDueDebt = null
  ),
  val weeklyTrends: List<WeeklyTrendPoint> = emptyList(),
  val priorWeekCarryOver: PriorWeekCarryOverCandidate? = null
)

private data class CoreLedgerBundle(
  val debts: List<DebtEntity>,
  val installments: List<InstallmentEntity>,
  val transactions: List<TransactionEntity>,
  val allowances: List<AllowanceRecordEntity>,
  val expenses: List<ExpenseEntity>
)

class BaonPlanViewModel(
  private val repository: BaonPlanRepository
) : ViewModel() {

  private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val messages: SharedFlow<String> = _messages.asSharedFlow()

  private val _isSessionUnlocked = MutableStateFlow(false)
  val isSessionUnlocked: StateFlow<Boolean> = _isSessionUnlocked.asStateFlow()

  fun setSessionUnlocked(unlocked: Boolean) {
    _isSessionUnlocked.value = unlocked
  }

  private val ledgerFlow = combine(
    repository.debtsFlow,
    repository.installmentsFlow,
    repository.transactionsFlow,
    repository.allowancesFlow,
    repository.expensesFlow
  ) { debts, installments, transactions, allowances, expenses ->
    CoreLedgerBundle(debts, installments, transactions, allowances, expenses)
  }

  val uiState: StateFlow<BaonPlanUiState> = combine(
    ledgerFlow,
    repository.reservesFlow,
    repository.settingsFlow,
    repository.appPreferencesFlow
  ) { ledger, reserves, settings, prefs ->
    val today = ManilaTime.today()
    val totalActiveDebt = FinancialCalculator.calculateTotalActiveDebt(ledger.debts)
    val progress = FinancialCalculator.calculateRepaymentProgress(ledger.debts)
    val weeklySummary = FinancialCalculator.calculateWeeklyBudgetSummary(
      today = today,
      settings = settings,
      allowances = ledger.allowances,
      expenses = ledger.expenses,
      reserves = reserves,
      transactions = ledger.transactions
    )
    val paymentOverview = FinancialCalculator.buildPaymentOverview(
      debts = ledger.debts,
      installments = ledger.installments,
      today = today
    )

    val minAllowance = settings.minWeeklyAllowanceCents
    val maxAllowance = settings.maxWeeklyAllowanceCents
    val foodBudget = settings.weeklyFoodBudgetCents

    val scenarioMin = FinancialCalculator.calculateScenarioPlan(
      scenarioName = "${MoneyFormatter.formatPesoCompact(minAllowance)} Conservative Week",
      allowanceCents = minAllowance,
      foodBudgetCents = foodBudget,
      otherExpensesCents = settings.weeklyOtherExpensesBudgetCents,
      plannedDebtReserveCents = settings.weeklyDebtReserveTargetCents,
      emergencySavingsCents = settings.weeklyEmergencySavingsTargetCents
    )
    val scenarioMax = FinancialCalculator.calculateScenarioPlan(
      scenarioName = "${MoneyFormatter.formatPesoCompact(maxAllowance)} Full Week",
      allowanceCents = maxAllowance,
      foodBudgetCents = foodBudget,
      otherExpensesCents = settings.weeklyOtherExpensesBudgetCents,
      plannedDebtReserveCents = settings.weeklyDebtReserveTargetCents,
      emergencySavingsCents = settings.weeklyEmergencySavingsTargetCents
    )

    val activeDebtReserveTotal = reserves
      .filter { it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE && it.status == ReserveStatus.RESERVED }
      .sumOf { it.remainingReservedCents }

    val emergencySavingsTotal = reserves
      .filter { it.reserveType == ReserveType.EMERGENCY_SAVINGS && it.status == ReserveStatus.RESERVED }
      .sumOf { it.remainingReservedCents }

    val duesReadiness = FinancialCalculator.calculateWeeklyDuesReadiness(
      today = today,
      paymentOverview = paymentOverview,
      weeklySummary = weeklySummary,
      activeDebtReserveTotalCents = activeDebtReserveTotal
    )
    val payoffInsights = FinancialCalculator.calculatePayoffInsights(
      debts = ledger.debts,
      installments = ledger.installments,
      paymentOverview = paymentOverview,
      today = today
    )
    val weeklyTrends = FinancialCalculator.calculateWeeklyTrends(
      today = today,
      allowances = ledger.allowances,
      expenses = ledger.expenses,
      transactions = ledger.transactions,
      weeksCount = 4
    )
    val priorWeekCarryOver = FinancialCalculator.calculatePriorWeekCarryOverCandidate(
      today = today,
      settings = settings,
      allowances = ledger.allowances,
      expenses = ledger.expenses,
      reserves = reserves,
      transactions = ledger.transactions,
      resolvedWeekIso = prefs.lastResolvedCarryOverWeekIso
    )

    BaonPlanUiState(
      isLoading = false,
      today = today,
      settings = settings,
      preferences = prefs,
      debts = ledger.debts,
      installments = ledger.installments,
      transactions = ledger.transactions,
      allowances = ledger.allowances,
      expenses = ledger.expenses,
      reserves = reserves,
      totalActiveDebtCents = totalActiveDebt,
      repaymentProgress = progress,
      weeklySummary = weeklySummary,
      paymentOverview = paymentOverview,
      scenario400 = scenarioMin,
      scenario500 = scenarioMax,
      activeDebtReserveTotalCents = activeDebtReserveTotal,
      emergencySavingsTotalCents = emergencySavingsTotal,
      duesReadiness = duesReadiness,
      payoffInsights = payoffInsights,
      weeklyTrends = weeklyTrends,
      priorWeekCarryOver = priorWeekCarryOver
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = BaonPlanUiState()
  )

  init {
    viewModelScope.launch {
      repository.ensureInitialized()
    }
  }

  fun emitMessage(text: String) {
    _messages.tryEmit(text)
  }

  fun recordAllowance(
    amountCents: Long,
    receivedDateIso: String,
    sourceLabel: String = "Weekly Allowance",
    notes: String = ""
  ) {
    viewModelScope.launch {
      runCatching {
        repository.recordAllowance(amountCents, receivedDateIso, sourceLabel, notes)
      }.onSuccess {
        emitMessage("Recorded allowance ${MoneyFormatter.formatPeso(amountCents)}")
      }.onFailure {
        emitMessage(it.message ?: "Could not record allowance")
      }
    }
  }

  fun deleteAllowance(allowanceId: Long) {
    viewModelScope.launch {
      repository.deleteAllowance(allowanceId)
      emitMessage("Allowance record removed")
    }
  }

  fun recordExpense(
    title: String,
    amountCents: Long,
    dateIso: String,
    category: ExpenseCategory,
    isSchoolDay: Boolean,
    notes: String = ""
  ) {
    viewModelScope.launch {
      runCatching {
        repository.recordExpense(title, amountCents, dateIso, category, isSchoolDay, notes)
      }.onSuccess {
        emitMessage("Logged ${category.label}: ${MoneyFormatter.formatPeso(amountCents)}")
      }.onFailure {
        emitMessage(it.message ?: "Could not save expense")
      }
    }
  }

  fun deleteExpense(expenseId: Long) {
    viewModelScope.launch {
      repository.deleteExpense(expenseId)
      emitMessage("Expense removed")
    }
  }

  fun createReserve(
    reserveType: ReserveType,
    amountCents: Long,
    dateIso: String,
    targetDebtId: Long? = null,
    notes: String = ""
  ) {
    viewModelScope.launch {
      runCatching {
        repository.createReserve(reserveType, amountCents, dateIso, targetDebtId, notes)
      }.onSuccess {
        val label = if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE) "Debt reserve" else "Emergency savings"
        emitMessage("$label added: ${MoneyFormatter.formatPeso(amountCents)}")
      }.onFailure {
        emitMessage(it.message ?: "Could not create reserve")
      }
    }
  }

  fun releaseReserve(reserveId: Long) {
    viewModelScope.launch {
      repository.releaseReserve(reserveId)
      emitMessage("Reserve released back to spending cash")
    }
  }

  fun withdrawEmergencySavings(
    amountCents: Long,
    dateIso: String,
    logAsExpense: Boolean,
    title: String,
    notes: String
  ) {
    viewModelScope.launch {
      val ok = repository.withdrawEmergencySavings(amountCents, dateIso, logAsExpense, title, notes)
      emitMessage(
        if (ok) {
          if (logAsExpense) "Used ${MoneyFormatter.formatPeso(amountCents)} from emergency savings"
          else "Moved ${MoneyFormatter.formatPeso(amountCents)} to this week's cash"
        } else {
          "Insufficient emergency savings balance"
        }
      )
    }
  }

  fun carryOverPriorWeek(prevWeekStartIso: String, amountCents: Long, todayIso: String) {
    viewModelScope.launch {
      repository.carryOverPriorWeekCash(prevWeekStartIso, amountCents, todayIso)
      emitMessage("Carried ${MoneyFormatter.formatPeso(amountCents)} into this week")
    }
  }

  fun sweepPriorWeekToReserve(prevWeekStartIso: String, reserveType: ReserveType, amountCents: Long) {
    viewModelScope.launch {
      repository.sweepPriorWeekCashToReserve(prevWeekStartIso, reserveType, amountCents)
      val dest = if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE) "debt reserve" else "emergency savings"
      emitMessage("Moved ${MoneyFormatter.formatPeso(amountCents)} to $dest")
    }
  }

  fun dismissPriorWeekCarryOver(prevWeekStartIso: String) {
    viewModelScope.launch {
      repository.dismissPriorWeekCarryOver(prevWeekStartIso)
    }
  }

  fun saveDebt(debt: DebtEntity) {
    viewModelScope.launch {
      repository.saveDebt(debt)
      emitMessage("Saved ${debt.name}")
    }
  }

  fun deleteDebt(debtId: Long, name: String) {
    viewModelScope.launch {
      repository.deleteDebt(debtId)
      emitMessage("Deleted $name")
    }
  }

  fun setDebtArchived(debtId: Long, archived: Boolean) {
    viewModelScope.launch {
      repository.setDebtArchived(debtId, archived)
      emitMessage(if (archived) "Loan archived" else "Loan restored")
    }
  }

  fun confirmDebtDate(debtId: Long, confirmedDateIso: String, generateSchedule: Boolean) {
    viewModelScope.launch {
      repository.confirmDebtDate(debtId, confirmedDateIso, generateSchedule)
      emitMessage("Due date confirmed for ${ManilaTime.formatDisplay(confirmedDateIso)}")
    }
  }

  fun generateSchedule(
    debtId: Long,
    startDateIso: String,
    count: Int,
    installmentAmountCents: Long,
    frequency: RepaymentFrequency,
    customIntervalDays: Int?,
    dateStatus: DueDateStatus
  ) {
    viewModelScope.launch {
      val ok = repository.generateInstallmentSchedule(
        debtId,
        startDateIso,
        count,
        installmentAmountCents,
        frequency,
        customIntervalDays,
        dateStatus
      )
      emitMessage(
        if (ok) "Installment schedule updated" else "Set a valid known frequency and start date first"
      )
    }
  }

  fun recordRepayment(
    debtId: Long,
    amountCents: Long,
    dateIso: String,
    installmentId: Long? = null,
    useDebtReserve: Boolean = false,
    notes: String = ""
  ) {
    viewModelScope.launch {
      runCatching {
        repository.recordRepayment(debtId, amountCents, dateIso, installmentId, useDebtReserve, notes)
      }.onSuccess { txId ->
        if (txId != null) {
          emitMessage("Repayment recorded: ${MoneyFormatter.formatPeso(amountCents)}")
        } else {
          emitMessage("Loan already has ₱0.00 remaining")
        }
      }.onFailure {
        emitMessage(it.message ?: "Could not record repayment")
      }
    }
  }

  fun markInstallmentPaid(installmentId: Long, dateIso: String, useDebtReserve: Boolean = false) {
    viewModelScope.launch {
      val txId = repository.markInstallmentPaid(installmentId, dateIso, useDebtReserve)
      emitMessage(if (txId != null) "Installment marked paid" else "Installment already paid")
    }
  }

  fun correctOrVoidTransaction(
    txId: Long,
    newAmountCents: Long?,
    newTitle: String?,
    newDateIso: String?,
    newNotes: String?,
    voidTransaction: Boolean,
    reason: String
  ) {
    viewModelScope.launch {
      runCatching {
        repository.correctOrVoidTransaction(
          transactionId = txId,
          newAmountCents = newAmountCents,
          newTitle = newTitle,
          newDateIso = newDateIso,
          newNotes = newNotes,
          voidTransaction = voidTransaction,
          reason = reason
        )
      }.onSuccess {
        emitMessage(if (voidTransaction) "Transaction voided and balances reverted" else "Transaction updated")
      }.onFailure {
        emitMessage(it.message ?: "Could not update transaction")
      }
    }
  }

  fun updateSettings(context: Context, settings: UserSettingsEntity) {
    viewModelScope.launch {
      repository.updateSettings(settings)
      ReminderScheduler.schedulePeriodicReminders(
        context = context.applicationContext,
        enabled = settings.remindersEnabled,
        reminderHour = settings.reminderHour,
        reminderMinute = settings.reminderMinute
      )
      emitMessage("Settings saved")
    }
  }

  fun saveBudgetDefaults(context: Context, settings: UserSettingsEntity, emergencyGoalCents: Long) {
    viewModelScope.launch {
      repository.updateSettings(settings)
      repository.setEmergencySavingsGoalCents(emergencyGoalCents)
      ReminderScheduler.schedulePeriodicReminders(
        context = context.applicationContext,
        enabled = settings.remindersEnabled,
        reminderHour = settings.reminderHour,
        reminderMinute = settings.reminderMinute
      )
      emitMessage("Budget defaults saved")
    }
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch {
      repository.setThemeMode(mode)
    }
  }

  fun setReminderLeadDays(days: Int) {
    viewModelScope.launch {
      repository.setReminderLeadDays(days)
      emitMessage("Advance notice set to $days day${if (days == 1) "" else "s"}")
    }
  }

  fun setEmergencySavingsGoalCents(goalCents: Long) {
    viewModelScope.launch {
      repository.setEmergencySavingsGoalCents(goalCents)
      emitMessage("Emergency fund goal updated")
    }
  }

  fun setAppLockEnabled(enabled: Boolean) {
    viewModelScope.launch {
      repository.setAppLockEnabled(enabled)
      emitMessage(if (enabled) "App lock enabled" else "App lock turned off")
    }
  }

  fun setExpensePresets(presets: List<ExpensePreset>) {
    viewModelScope.launch {
      repository.setExpensePresets(presets)
      emitMessage("Quick presets saved")
    }
  }

  fun markNotificationPermissionRequested() {
    viewModelScope.launch {
      repository.markNotificationPermissionRequested()
    }
  }

  fun sendTestNotification(context: Context) {
    val shown = NotificationHelper.showNotification(
      context = context,
      channelId = NotificationHelper.CHANNEL_PAYMENTS,
      notificationId = NotificationHelper.NOTIFICATION_ID_TEST,
      title = "BaonPlan Reminder Check",
      body = "Payment & weekly baon reminders are active on this device.",
      highPriority = true
    )
    emitMessage(
      if (shown) "Test notification sent"
      else "Notification blocked — check Android notification permission or system settings"
    )
  }

  fun runReminderWorkerNow(context: Context) {
    ReminderScheduler.runImmediateCheck(context)
    emitMessage("Scheduled immediate payment & allowance check")
  }

  fun exportBackupJson(context: Context, uri: Uri) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          val payload = repository.createBackupPayload()
          val json = BackupSerializer.encodeBackupJson(payload)
          context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open selected file for writing.")
          repository.markBackupExportedNow()
          payload.debts.size
        }
      }
      result.onSuccess { debtCount ->
        emitMessage("Backup exported ($debtCount loans saved)")
      }.onFailure {
        emitMessage("Export failed: ${it.message}")
      }
    }
  }

  fun exportTransactionsCsv(context: Context, uri: Uri) {
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          val txs = repository.createBackupPayload().transactions
          val csv = BackupSerializer.encodeTransactionsCsv(txs)
          context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(csv.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open CSV destination.")
          txs.size
        }
      }
      result.onSuccess { count ->
        emitMessage("Exported $count transactions to CSV")
      }.onFailure {
        emitMessage("CSV export failed: ${it.message}")
      }
    }
  }

  suspend fun readAndParseBackup(context: Context, uri: Uri): Result<BaonPlanBackupPayload> =
    withContext(Dispatchers.IO) {
      runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
          stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("Unable to read selected backup file.")
        BackupSerializer.decodeBackupJson(raw).getOrThrow()
      }
    }

  fun restoreBackup(payload: BaonPlanBackupPayload) {
    viewModelScope.launch {
      repository.restoreFromBackup(payload)
      emitMessage("Backup restored (${payload.debts.size} loans, ${payload.transactions.size} transactions)")
    }
  }

  fun clearAllData() {
    viewModelScope.launch {
      repository.clearAllData()
      emitMessage("All financial records cleared")
    }
  }

  class Factory(private val repository: BaonPlanRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return BaonPlanViewModel(repository) as T
    }
  }
}
