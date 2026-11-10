package com.baonplan.finance.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.baonplan.finance.MainActivity
import com.baonplan.finance.R
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.budget.CashFlowBudgetEngine
import com.baonplan.finance.domain.budget.PaymentGroupingEngine
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BaonWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray
  ) {
    if (appWidgetIds.isEmpty()) return
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        updateWidgetsNow(appContext, appWidgetManager, appWidgetIds)
      } finally {
        pendingResult.finish()
      }
    }
  }

  companion object {
    const val EXTRA_QUICK_ACTION = "baonplan_quick_action"
    const val ACTION_ADD_EXPENSE = "add_expense"
    const val ACTION_RECORD_PAYMENT = "record_payment"

    fun requestUpdate(context: Context) {
      val appContext = context.applicationContext
      val manager = AppWidgetManager.getInstance(appContext) ?: return
      val ids = manager.getAppWidgetIds(ComponentName(appContext, BaonWidgetProvider::class.java))
      if (ids.isEmpty()) return
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        updateWidgetsNow(appContext, manager, ids)
      }
    }

    private suspend fun updateWidgetsNow(
      context: Context,
      manager: AppWidgetManager,
      appWidgetIds: IntArray
    ) {
      val db = BaonPlanDatabase.getInstance(context)
      val settings = db.userSettingsDao().getSettings() ?: DefaultSettingsProvider.defaultSettings()
      val debts = db.debtDao().getAll()
      val installments = db.installmentDao().getAll()
      val transactions = db.transactionDao().getAll()
      val allowances = db.allowanceRecordDao().getAll()
      val expenses = db.expenseDao().getAll()
      val reserves = db.debtReserveDao().getAll()
      val budgetProfile = db.budgetProfileDao().getProfile()
      val incomeSources = db.incomeSourceDao().getAll()
      val incomeOccurrences = db.incomeOccurrenceDao().getAll()
      val budgetAllocations = db.budgetAllocationDao().getAll()

      val today = ManilaTime.today()
      val weekly = FinancialCalculator.calculateWeeklyBudgetSummary(
        today = today,
        settings = settings,
        allowances = allowances,
        expenses = expenses,
        reserves = reserves,
        transactions = transactions
      )
      val budgetOverview = CashFlowBudgetEngine.calculateOverview(
        today = today,
        profile = budgetProfile,
        sources = incomeSources,
        occurrences = incomeOccurrences,
        allocations = budgetAllocations,
        allowances = allowances,
        expenses = expenses,
        reserves = reserves,
        transactions = transactions,
        debts = debts,
        installments = installments
      )
      val grouping = PaymentGroupingEngine.buildGroupedPayments(
        debts = debts,
        installments = installments,
        today = today
      )

      val displayMoneyCents = if (budgetOverview.isConfigured) {
        budgetOverview.projectedRemainingCents
      } else {
        weekly.availableSpendingMoneyCents
      }
      val moneyText = MoneyFormatter.formatPeso(displayMoneyCents)
      val moneyColor = ContextCompat.getColor(
        context,
        if (displayMoneyCents < 0L) R.color.widget_danger else R.color.widget_positive
      )

      val focusGroup = grouping.overdueGroup ?: grouping.nextGroup
      val firstUnconfirmed = grouping.undatedOrUnconfirmedPayments.firstOrNull()
      val nextText = when {
        focusGroup != null && focusGroup.isMultiItemGroup -> {
          val prefix = if (focusGroup.isOverdueGroup) "Overdue" else "Next"
          val amt = if (focusGroup.knownTotalCents > 0L) {
            MoneyFormatter.formatPesoCompact(focusGroup.knownTotalCents) +
              if (focusGroup.hasIncompleteAmounts) "+" else ""
          } else {
            "Amount unavailable"
          }
          "$prefix (${focusGroup.dateRangeLabel}): $amt (${focusGroup.distinctLoansCount} loans)"
        }
        focusGroup != null -> {
          val single = focusGroup.items.first()
          val dateLabel = buildString {
            if (single.isEstimatedDate) append("Est. ")
            append(focusGroup.dateRangeLabel)
          }
          val amt = single.unpaidAmountCents?.let { MoneyFormatter.formatPesoCompact(it) } ?: "Amount unavailable"
          val prefix = if (focusGroup.isOverdueGroup) "Overdue" else "Next"
          "$prefix: ${single.debtName} · $amt ($dateLabel)"
        }
        firstUnconfirmed != null -> "Date unconfirmed: ${firstUnconfirmed.debtName}"
        else -> "No upcoming payments"
      }

      val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      val openAppPending = PendingIntent.getActivity(
        context,
        2001,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val quickExpenseIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra(EXTRA_QUICK_ACTION, ACTION_ADD_EXPENSE)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      val quickExpensePending = PendingIntent.getActivity(
        context,
        2002,
        quickExpenseIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val views = RemoteViews(context.packageName, R.layout.widget_baon_summary).apply {
        setTextViewText(R.id.widget_money_left, moneyText)
        setTextColor(R.id.widget_money_left, moneyColor)
        setTextViewText(R.id.widget_next_payment, nextText)
        setOnClickPendingIntent(R.id.widget_root, openAppPending)
        setOnClickPendingIntent(R.id.widget_add_expense_btn, quickExpensePending)
      }

      for (id in appWidgetIds) {
        manager.updateAppWidget(id, views)
      }
    }
  }
}
