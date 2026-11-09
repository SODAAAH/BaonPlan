package com.baonplan.finance.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.baonplan.finance.data.backup.BaonPlanBackupPayload
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.notifications.NotificationHelper
import com.baonplan.finance.ui.components.AddExpenseDialog
import com.baonplan.finance.ui.components.AdjustBudgetSheet
import com.baonplan.finance.ui.components.CalmDivider
import com.baonplan.finance.ui.components.ConfirmDueDateDialog
import com.baonplan.finance.ui.components.ConfirmMarkInstallmentDialog
import com.baonplan.finance.ui.components.CorrectTransactionDialog
import com.baonplan.finance.ui.components.CreateReserveDialog
import com.baonplan.finance.ui.components.DebtEditorDialog
import com.baonplan.finance.ui.components.GenerateScheduleDialog
import com.baonplan.finance.ui.components.RecordAllowanceDialog
import com.baonplan.finance.ui.components.RecordRepaymentDialog
import com.baonplan.finance.ui.components.ScanDebtSheet
import com.baonplan.finance.ui.components.UseEmergencySavingsDialog
import com.baonplan.finance.widget.BaonWidgetProvider
import com.baonplan.finance.ui.navigation.AboutKey
import com.baonplan.finance.ui.navigation.AllowanceBudgetKey
import com.baonplan.finance.ui.navigation.AppearanceKey
import com.baonplan.finance.ui.navigation.BackupKey
import com.baonplan.finance.ui.navigation.BudgetDefaultsKey
import com.baonplan.finance.ui.navigation.CalendarKey
import com.baonplan.finance.ui.navigation.DashboardKey
import com.baonplan.finance.ui.navigation.DebtDetailKey
import com.baonplan.finance.ui.navigation.DebtsKey
import com.baonplan.finance.ui.navigation.MoreKey
import com.baonplan.finance.ui.navigation.NotificationsKey
import com.baonplan.finance.ui.navigation.ReportsKey
import com.baonplan.finance.ui.navigation.TransactionsKey
import com.baonplan.finance.ui.screens.AboutScreen
import com.baonplan.finance.ui.screens.AllowanceBudgetScreen
import com.baonplan.finance.ui.screens.AppearanceScreen
import com.baonplan.finance.ui.screens.BackupRestoreScreen
import com.baonplan.finance.ui.screens.BudgetDefaultsScreen
import com.baonplan.finance.ui.screens.DashboardScreen
import com.baonplan.finance.ui.screens.DebtDetailScreen
import com.baonplan.finance.ui.screens.DebtsScreen
import com.baonplan.finance.ui.screens.MoreScreen
import com.baonplan.finance.ui.screens.NotificationsScreen
import com.baonplan.finance.ui.screens.PaymentCalendarScreen
import com.baonplan.finance.ui.screens.ReportsScreen
import com.baonplan.finance.ui.screens.TransactionsScreen
import kotlinx.coroutines.launch

private data class PrimaryNavItem(
  val key: NavKey,
  val label: String,
  val icon: ImageVector
)

private val primaryTabs = listOf(
  PrimaryNavItem(DashboardKey, "Home", Icons.Outlined.Home),
  PrimaryNavItem(AllowanceBudgetKey, "Budget", Icons.Outlined.AccountBalanceWallet),
  PrimaryNavItem(DebtsKey, "Debts", Icons.Outlined.AccountBalance),
  PrimaryNavItem(MoreKey, "More", Icons.Outlined.MoreHoriz)
)

private val primaryTabKeys: Set<NavKey> = primaryTabs.map { it.key }.toSet()

private fun secondaryTitleFor(key: NavKey): String = when (key) {
  CalendarKey -> "Payment calendar"
  TransactionsKey -> "Transaction history"
  ReportsKey -> "Reports"
  AppearanceKey -> "Appearance"
  NotificationsKey -> "Notifications"
  BudgetDefaultsKey -> "Budget defaults"
  BackupKey -> "Backup and restore"
  AboutKey -> "About BaonPlan"
  else -> "BaonPlan"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaonPlanRoot(
  viewModel: BaonPlanViewModel,
  pendingQuickAction: String? = null,
  onConsumeQuickAction: () -> Unit = {},
  onSuppressNextRelock: () -> Unit = {},
  onToggleAppLock: (Boolean) -> Unit = {}
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val scanState by viewModel.scanState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val backStack = rememberNavBackStack(DashboardKey)
  val currentKey = backStack.lastOrNull() ?: DashboardKey

  var hasNotificationPermission by remember {
    mutableStateOf(NotificationHelper.hasNotificationPermission(context))
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasNotificationPermission = granted || NotificationHelper.hasNotificationPermission(context)
    viewModel.markNotificationPermissionRequested()
    viewModel.emitMessage(
      if (hasNotificationPermission) "Notification reminders enabled"
      else "Notifications denied — you can enable them in Android Settings"
    )
  }

  val requestNotifPermission: () -> Unit = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      hasNotificationPermission = true
    }
  }

  val openNotificationSettings: () -> Unit = {
    onSuppressNextRelock()
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
  }

  val exportJsonLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
  ) { uri ->
    if (uri != null) viewModel.exportBackupJson(context, uri)
  }

  val exportCsvLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/csv")
  ) { uri ->
    if (uri != null) viewModel.exportTransactionsCsv(context, uri)
  }

  var pendingBackupPayload by remember { mutableStateOf<BaonPlanBackupPayload?>(null) }
  val importJsonLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      scope.launch {
        viewModel.readAndParseBackup(context, uri)
          .onSuccess { pendingBackupPayload = it }
          .onFailure { viewModel.emitMessage("Invalid backup: ${it.message}") }
      }
    }
  }

  // Modal bottom sheets & dialogs state
  var showAllowanceDialog by remember { mutableStateOf(false) }
  var showExpenseDialog by remember { mutableStateOf(false) }
  var showAdjustBudgetSheet by remember { mutableStateOf(false) }
  var repaymentTargetDebtId by remember { mutableStateOf<Long?>(null) }
  var showRepaymentDialog by remember { mutableStateOf(false) }
  var debtEditorTarget by remember { mutableStateOf<DebtEntity?>(null) }
  var showDebtEditor by remember { mutableStateOf(false) }
  var reserveInitialType by remember { mutableStateOf(ReserveType.DEBT_REPAYMENT_RESERVE) }
  var showReserveDialog by remember { mutableStateOf(false) }
  var scheduleTargetDebt by remember { mutableStateOf<DebtEntity?>(null) }
  var confirmDateTargetDebt by remember { mutableStateOf<DebtEntity?>(null) }
  var correctionTargetTx by remember { mutableStateOf<TransactionEntity?>(null) }
  var showUseEmergencyDialog by remember { mutableStateOf(false) }
  var markInstallmentTarget by remember { mutableStateOf<Pair<DebtEntity, InstallmentEntity>?>(null) }

  LaunchedEffect(Unit) {
    viewModel.messages.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  LaunchedEffect(
    state.isLoading,
    state.weeklySummary.availableSpendingMoneyCents,
    state.totalActiveDebtCents,
    state.paymentOverview
  ) {
    if (!state.isLoading) {
      BaonWidgetProvider.requestUpdate(context)
    }
  }

  val activeDebts = remember(state.debts) {
    state.debts.filter { it.status == DebtStatus.ACTIVE && it.remainingBalanceCents > 0L }
  }

  LaunchedEffect(state.isLoading, pendingQuickAction) {
    if (!state.isLoading && pendingQuickAction != null) {
      when (pendingQuickAction) {
        BaonWidgetProvider.ACTION_ADD_EXPENSE -> {
          showExpenseDialog = true
        }
        BaonWidgetProvider.ACTION_RECORD_PAYMENT -> {
          repaymentTargetDebtId = activeDebts.firstOrNull()?.id
          showRepaymentDialog = true
        }
      }
      onConsumeQuickAction()
    }
  }

  fun navigateToTopLevel(target: NavKey) {
    if (currentKey == target) return
    while (backStack.size > 1) {
      backStack.removeLastOrNull()
    }
    if (target == DashboardKey) {
      if (backStack.firstOrNull() != DashboardKey) {
        backStack.add(DashboardKey)
      }
    } else {
      backStack.add(target)
    }
  }

  fun pushScreen(target: NavKey) {
    if (currentKey != target) {
      backStack.add(target)
    }
  }

  if (state.isLoading) {
    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing
    ) { pad ->
      Box(
        modifier = Modifier.fillMaxSize().padding(pad),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator()
      }
    }
    return
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isExpanded = maxWidth >= 600.dp
    val isPrimaryScreen = currentKey in primaryTabKeys
    val showSecondaryTopBar = !isPrimaryScreen && currentKey !is DebtDetailKey

    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        if (showSecondaryTopBar) {
          TopAppBar(
            title = {
              Text(
                text = secondaryTitleFor(currentKey),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            },
            navigationIcon = {
              IconButton(onClick = { if (backStack.size > 1) backStack.removeLastOrNull() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.background,
              titleContentColor = MaterialTheme.colorScheme.onBackground
            )
          )
        }
      },
      bottomBar = {
        if (isPrimaryScreen && !isExpanded) {
          Column {
            CalmDivider()
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
              tonalElevation = 0.dp
            ) {
              primaryTabs.forEach { tab ->
                NavigationBarItem(
                  selected = currentKey == tab.key,
                  onClick = { navigateToTopLevel(tab.key) },
                  icon = { Icon(tab.icon, contentDescription = tab.label) },
                  label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                  colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                )
              }
            }
          }
        }
      }
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
      ) {
        if (isPrimaryScreen && isExpanded) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface
          ) {
            Spacer(modifier = Modifier.height(12.dp))
            primaryTabs.forEach { tab ->
              NavigationRailItem(
                selected = currentKey == tab.key,
                onClick = { navigateToTopLevel(tab.key) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationRailItemDefaults.colors(
                  selectedIconColor = MaterialTheme.colorScheme.primary,
                  selectedTextColor = MaterialTheme.colorScheme.primary,
                  indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                  unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                  unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
              )
            }
          }
        }

        NavDisplay(
          backStack = backStack,
          onBack = {
            if (backStack.size > 1) {
              backStack.removeLastOrNull()
            }
          },
          transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(180))
          },
          popTransitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(180))
          },
          modifier = Modifier.weight(1f).fillMaxSize(),
          entryProvider = entryProvider {
            entry<DashboardKey> {
              DashboardScreen(
                state = state,
                onQuickAddExpense = { showExpenseDialog = true },
                onQuickRecordRepayment = {
                  repaymentTargetDebtId = activeDebts.firstOrNull()?.id
                  showRepaymentDialog = true
                },
                onQuickRecordAllowance = { showAllowanceDialog = true },
                onQuickAddDebt = {
                  debtEditorTarget = null
                  showDebtEditor = true
                },
                onOpenDebts = { navigateToTopLevel(DebtsKey) },
                onOpenDebtDetail = { id -> pushScreen(DebtDetailKey(id)) },
                onOpenCalendar = { pushScreen(CalendarKey) }
              )
            }

            entry<AllowanceBudgetKey> {
              AllowanceBudgetScreen(
                state = state,
                onRecordAllowanceQuick = { cents ->
                  viewModel.recordAllowance(
                    amountCents = cents,
                    receivedDateIso = ManilaTime.formatIso(state.today),
                    sourceLabel = "Weekly Allowance"
                  )
                },
                onOpenRecordAllowanceDialog = { showAllowanceDialog = true },
                onDeleteAllowance = { viewModel.deleteAllowance(it) },
                onQuickSchoolMealExpense = {
                  viewModel.recordExpense(
                    title = "School food",
                    amountCents = state.settings.foodPerSchoolDayCents,
                    dateIso = ManilaTime.formatIso(state.today),
                    category = ExpenseCategory.SCHOOL_FOOD,
                    isSchoolDay = true
                  )
                },
                onQuickPresetExpense = { preset ->
                  viewModel.recordExpense(
                    title = preset.label,
                    amountCents = preset.amountCents,
                    dateIso = ManilaTime.formatIso(state.today),
                    category = preset.category,
                    isSchoolDay = preset.isSchoolDay
                  )
                },
                onDeleteExpense = { viewModel.deleteExpense(it) },
                onOpenAddExpenseDialog = { showExpenseDialog = true },
                onOpenAdjustBudget = { showAdjustBudgetSheet = true },
                onOpenCreateReserveDialog = { type ->
                  reserveInitialType = type
                  showReserveDialog = true
                },
                onReleaseReserve = { viewModel.releaseReserve(it) },
                onOpenUseEmergencySavings = { showUseEmergencyDialog = true },
                onCarryOverPriorWeek = { candidate ->
                  viewModel.carryOverPriorWeek(
                    prevWeekStartIso = candidate.weekStartIso,
                    amountCents = candidate.leftoverCents,
                    todayIso = ManilaTime.formatIso(state.today)
                  )
                },
                onSweepPriorWeekToReserve = { candidate, reserveType ->
                  viewModel.sweepPriorWeekToReserve(
                    prevWeekStartIso = candidate.weekStartIso,
                    reserveType = reserveType,
                    amountCents = candidate.leftoverCents
                  )
                },
                onDismissCarryOver = { weekIso ->
                  viewModel.dismissPriorWeekCarryOver(weekIso)
                }
              )
            }

            entry<DebtsKey> {
              DebtsScreen(
                state = state,
                onOpenDebtDetail = { id -> pushScreen(DebtDetailKey(id)) },
                onAddDebt = {
                  debtEditorTarget = null
                  showDebtEditor = true
                },
                onScanDebt = {
                  viewModel.openScanDebtSheet(context)
                }
              )
            }

            entry<MoreKey> {
              MoreScreen(
                state = state,
                hasNotificationPermission = hasNotificationPermission,
                onToggleAppLock = onToggleAppLock,
                onOpenCalendar = { pushScreen(CalendarKey) },
                onOpenTransactions = { pushScreen(TransactionsKey) },
                onOpenReports = { pushScreen(ReportsKey) },
                onOpenAppearance = { pushScreen(AppearanceKey) },
                onOpenNotifications = { pushScreen(NotificationsKey) },
                onOpenBudgetDefaults = { pushScreen(BudgetDefaultsKey) },
                onOpenBackup = { pushScreen(BackupKey) },
                onOpenAbout = { pushScreen(AboutKey) }
              )
            }

            entry<CalendarKey> {
              PaymentCalendarScreen(
                state = state,
                onOpenDebtDetail = { id -> pushScreen(DebtDetailKey(id)) },
                onConfirmDebtDate = { confirmDateTargetDebt = it },
                onRecordPaymentForDebt = { debtId ->
                  repaymentTargetDebtId = debtId
                  showRepaymentDialog = true
                }
              )
            }

            entry<TransactionsKey> {
              TransactionsScreen(
                state = state,
                onSelectTransactionForCorrection = { correctionTargetTx = it }
              )
            }

            entry<ReportsKey> {
              ReportsScreen(
                state = state,
                onExportCsv = {
                  onSuppressNextRelock()
                  exportCsvLauncher.launch("baonplan-transactions-${ManilaTime.formatIso(state.today)}.csv")
                }
              )
            }

            entry<AppearanceKey> {
              AppearanceScreen(
                selectedMode = state.preferences.themeMode,
                onSelectMode = { mode -> viewModel.setThemeMode(mode) }
              )
            }

            entry<NotificationsKey> {
              NotificationsScreen(
                state = state,
                hasNotificationPermission = hasNotificationPermission,
                onSaveSettings = { updated -> viewModel.updateSettings(context, updated) },
                onSaveLeadDays = { days -> viewModel.setReminderLeadDays(days) },
                onRequestNotificationPermission = requestNotifPermission,
                onOpenSystemNotificationSettings = openNotificationSettings,
                onSendTestNotification = { viewModel.sendTestNotification(context) },
                onRunReminderCheckNow = { viewModel.runReminderWorkerNow(context) }
              )
            }

            entry<BudgetDefaultsKey> {
              BudgetDefaultsScreen(
                settings = state.settings,
                emergencyGoalCents = state.preferences.emergencySavingsGoalCents,
                expensePresets = state.preferences.expensePresets,
                onSaveDefaultsAndGoal = { updated, goal ->
                  viewModel.saveBudgetDefaults(context, updated, goal)
                },
                onSaveExpensePresets = { presets -> viewModel.setExpensePresets(presets) }
              )
            }

            entry<BackupKey> {
              BackupRestoreScreen(
                lastBackupEpochMillis = state.preferences.lastBackupEpochMillis,
                hasAnyRecords = state.debts.isNotEmpty() ||
                  state.transactions.isNotEmpty() ||
                  state.allowances.isNotEmpty() ||
                  state.expenses.isNotEmpty() ||
                  state.reserves.isNotEmpty(),
                onExportBackupJson = {
                  onSuppressNextRelock()
                  exportJsonLauncher.launch("baonplan-backup-${ManilaTime.formatIso(state.today)}.json")
                },
                onImportBackupJson = {
                  onSuppressNextRelock()
                  importJsonLauncher.launch(arrayOf("application/json", "*/*"))
                },
                onExportTransactionsCsv = {
                  onSuppressNextRelock()
                  exportCsvLauncher.launch("baonplan-transactions-${ManilaTime.formatIso(state.today)}.csv")
                },
                onClearAllData = { viewModel.clearAllData() }
              )
            }

            entry<AboutKey> {
              AboutScreen()
            }

            entry<DebtDetailKey> { key ->
              val debt = state.debts.firstOrNull { it.id == key.debtId }
              val debtInstallments = state.installments.filter { it.debtId == key.debtId }
              val debtTransactions = state.transactions.filter { it.debtId == key.debtId }
              val debtAttachments = state.attachments.filter { it.debtId == key.debtId }
              DebtDetailScreen(
                debt = debt,
                installments = debtInstallments,
                transactions = debtTransactions,
                attachments = debtAttachments,
                resolveAttachmentFile = viewModel::resolveAttachmentFile,
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                onEditDebt = {
                  debtEditorTarget = it
                  showDebtEditor = true
                },
                onRecordPayment = {
                  repaymentTargetDebtId = it.id
                  showRepaymentDialog = true
                },
                onConfirmDate = { confirmDateTargetDebt = it },
                onGenerateSchedule = { scheduleTargetDebt = it },
                onMarkInstallmentPaid = { inst ->
                  if (debt != null) {
                    markInstallmentTarget = debt to inst
                  }
                },
                onArchiveToggle = { target ->
                  viewModel.setDebtArchived(
                    debtId = target.id,
                    archived = target.status != DebtStatus.ARCHIVED
                  )
                },
                onDeleteDebt = { target ->
                  viewModel.deleteDebt(target.id, target.name)
                },
                onCorrectTransaction = { correctionTargetTx = it }
              )
            }
          }
        )
      }
    }
  }

  // Modal Bottom Sheets & Dialogs
  if (showAllowanceDialog) {
    RecordAllowanceDialog(
      defaultAmountCents = state.settings.conservativeAllowanceCents,
      minPresetCents = state.settings.minWeeklyAllowanceCents,
      maxPresetCents = state.settings.maxWeeklyAllowanceCents,
      today = state.today,
      onConfirm = { amount, dateIso, source, notes ->
        viewModel.recordAllowance(amount, dateIso, source, notes)
      },
      onDismiss = { showAllowanceDialog = false }
    )
  }

  if (showExpenseDialog) {
    AddExpenseDialog(
      defaultFoodCents = state.settings.foodPerSchoolDayCents,
      presets = state.preferences.expensePresets,
      today = state.today,
      onConfirm = { title, amount, dateIso, category, isSchoolDay, notes ->
        viewModel.recordExpense(title, amount, dateIso, category, isSchoolDay, notes)
      },
      onDismiss = { showExpenseDialog = false }
    )
  }

  if (showAdjustBudgetSheet) {
    AdjustBudgetSheet(
      settings = state.settings,
      onSave = { updated -> viewModel.updateSettings(context, updated) },
      onDismiss = { showAdjustBudgetSheet = false }
    )
  }

  if (showRepaymentDialog) {
    RecordRepaymentDialog(
      activeDebts = activeDebts,
      initialDebtId = repaymentTargetDebtId,
      availableDebtReserveCents = state.activeDebtReserveTotalCents,
      today = state.today,
      onConfirm = { debtId, amount, dateIso, useReserve, notes ->
        viewModel.recordRepayment(debtId, amount, dateIso, null, useReserve, notes)
      },
      onDismiss = { showRepaymentDialog = false },
      onAddDebt = {
        debtEditorTarget = null
        showDebtEditor = true
      }
    )
  }

  if (showDebtEditor) {
    DebtEditorDialog(
      existing = debtEditorTarget,
      onSave = { viewModel.saveDebt(it) },
      onDismiss = { showDebtEditor = false }
    )
  }

  if (scanState.isSheetOpen) {
    ScanDebtSheet(
      scanState = scanState,
      onSuppressNextRelock = onSuppressNextRelock,
      onAddPickedUris = { uris -> viewModel.addPickedScanUris(context, uris) },
      onPrepareCameraUri = { viewModel.prepareCameraCaptureUri(context) },
      onCameraCaptureResult = { captured -> viewModel.onCameraCaptureResult(context, captured) },
      onRotateItem = { id -> viewModel.rotateStagedScreenshot(id) },
      onRemoveItem = { id -> viewModel.removeStagedScreenshot(id) },
      onAnalyze = { viewModel.analyzeStagedScreenshots() },
      onSelectCandidateDraft = { draft -> viewModel.selectCandidateDraftForReview(draft) },
      onEnterManually = {
        viewModel.closeAndClearScanSession(context)
        debtEditorTarget = null
        showDebtEditor = true
      },
      onDismiss = { viewModel.closeAndClearScanSession(context) }
    )
  }

  scanState.activeDraftForReview?.let { draft ->
    DebtEditorDialog(
      existing = null,
      prefillDraft = draft,
      existingDebts = state.debts,
      existingInstallments = state.installments,
      partialScanWarnings = scanState.partialWarnings,
      isSavingImport = scanState.isSavingDraft,
      onSave = {},
      onSaveImported = { confirmedDebt, scheduleRows, attach ->
        viewModel.saveScannedDebt(
          context = context,
          draft = draft,
          confirmedDebt = confirmedDebt,
          scheduleRows = scheduleRows,
          attachScreenshots = attach
        )
      },
      onDismiss = { viewModel.dismissActiveDraftReview(context) }
    )
  }

  if (showReserveDialog) {
    CreateReserveDialog(
      initialType = reserveInitialType,
      activeDebts = activeDebts,
      today = state.today,
      onConfirm = { type, amount, dateIso, targetDebtId, notes ->
        viewModel.createReserve(type, amount, dateIso, targetDebtId, notes)
      },
      onDismiss = { showReserveDialog = false }
    )
  }

  scheduleTargetDebt?.let { target ->
    GenerateScheduleDialog(
      debt = target,
      today = state.today,
      onGenerate = { startIso, count, amount, freq, customDays, dateStatus ->
        viewModel.generateSchedule(target.id, startIso, count, amount, freq, customDays, dateStatus)
      },
      onDismiss = { scheduleTargetDebt = null }
    )
  }

  confirmDateTargetDebt?.let { target ->
    ConfirmDueDateDialog(
      debt = target,
      today = state.today,
      onConfirm = { dateIso, genSchedule ->
        viewModel.confirmDebtDate(target.id, dateIso, genSchedule)
      },
      onDismiss = { confirmDateTargetDebt = null }
    )
  }

  correctionTargetTx?.let { target ->
    CorrectTransactionDialog(
      tx = target,
      onSaveEdit = { newAmount, newTitle, newDate, newNotes, reason ->
        viewModel.correctOrVoidTransaction(
          txId = target.id,
          newAmountCents = newAmount,
          newTitle = newTitle,
          newDateIso = newDate,
          newNotes = newNotes,
          voidTransaction = false,
          reason = reason
        )
      },
      onVoid = { reason ->
        viewModel.correctOrVoidTransaction(
          txId = target.id,
          newAmountCents = null,
          newTitle = null,
          newDateIso = null,
          newNotes = null,
          voidTransaction = true,
          reason = reason
        )
      },
      onDismiss = { correctionTargetTx = null }
    )
  }

  if (showUseEmergencyDialog) {
    UseEmergencySavingsDialog(
      availableSavingsCents = state.emergencySavingsTotalCents,
      today = state.today,
      onConfirm = { amount, dateIso, logAsExpense, title, notes ->
        viewModel.withdrawEmergencySavings(amount, dateIso, logAsExpense, title, notes)
      },
      onDismiss = { showUseEmergencyDialog = false }
    )
  }

  markInstallmentTarget?.let { (targetDebt, inst) ->
    ConfirmMarkInstallmentDialog(
      debtName = targetDebt.name,
      installment = inst,
      availableDebtReserveCents = state.activeDebtReserveTotalCents,
      onConfirm = { useReserve ->
        viewModel.markInstallmentPaid(
          installmentId = inst.id,
          dateIso = ManilaTime.formatIso(state.today),
          useDebtReserve = useReserve
        )
      },
      onDismiss = { markInstallmentTarget = null }
    )
  }

  pendingBackupPayload?.let { payload ->
    AlertDialog(
      onDismissRequest = { pendingBackupPayload = null },
      title = { Text("Restore backup?") },
      text = {
        Text(
          "Replace all current local records with this backup?\n\n" +
            "• Debts: ${payload.debts.size}\n" +
            "• Installments: ${payload.installments.size}\n" +
            "• Transactions: ${payload.transactions.size}\n" +
            "• Allowances: ${payload.allowanceRecords.size}\n" +
            "• Expenses: ${payload.expenses.size}\n" +
            "• Exported: ${payload.exportedAtIso}"
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.restoreBackup(payload)
            pendingBackupPayload = null
          }
        ) {
          Text("Restore")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingBackupPayload = null }) { Text("Cancel") }
      }
    )
  }
}
