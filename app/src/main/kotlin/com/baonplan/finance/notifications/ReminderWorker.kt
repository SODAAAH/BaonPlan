package com.baonplan.finance.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.SettingsPreferencesDataStore
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import com.baonplan.finance.widget.BaonWidgetProvider
import kotlinx.coroutines.flow.first

class ReminderWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val db = BaonPlanDatabase.getInstance(applicationContext)
    val prefs = SettingsPreferencesDataStore(applicationContext)
    val settings = db.userSettingsDao().getSettings() ?: DefaultSettingsProvider.defaultSettings()
    val appPrefs = prefs.preferencesFlow.first()
    val leadDays = appPrefs.reminderLeadDays.toLong().coerceIn(1L, 7L)

    if (!settings.remindersEnabled) {
      BaonWidgetProvider.requestUpdate(applicationContext)
      return Result.success()
    }

    val today = ManilaTime.today()
    val debts = db.debtDao().getAll()
    val installments = db.installmentDao().getAll()
    val allowances = db.allowanceRecordDao().getAll()

    val overview = FinancialCalculator.buildPaymentOverview(debts, installments, today)

    if (overview.overduePayments.isNotEmpty()) {
      val totalOverdue = overview.overduePayments.sumOf { it.amountCents }
      val first = overview.overduePayments.first()
      val tag = if (first.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION) " (Estimated date)" else ""
      val extra = if (overview.overduePayments.size > 1) " (+${overview.overduePayments.size - 1} more)" else ""
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_OVERDUE,
        title = "Overdue Payment Alert (${overview.overduePayments.size})",
        body = "${first.debtName}$tag$extra · ${MoneyFormatter.formatPeso(totalOverdue)} overdue. Open Calendar to review or confirm.",
        highPriority = true
      )
    }

    if (overview.dueTodayPayments.isNotEmpty()) {
      val first = overview.dueTodayPayments.first()
      val tag = if (first.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION) " (Estimated)" else ""
      val extra = if (overview.dueTodayPayments.size > 1) " (+${overview.dueTodayPayments.size - 1} more)" else ""
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_DUE_TODAY,
        title = "Payment Due Today",
        body = "${first.debtName}$tag$extra: ${MoneyFormatter.formatPeso(first.amountCents)} due today (${ManilaTime.formatDisplay(today)}).",
        highPriority = true
      )
    }

    if (overview.dueTomorrowPayments.isNotEmpty()) {
      val first = overview.dueTomorrowPayments.first()
      val tag = if (first.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION) " (Estimated)" else ""
      val extra = if (overview.dueTomorrowPayments.size > 1) " (+${overview.dueTomorrowPayments.size - 1} more)" else ""
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_DUE_TOMORROW,
        title = "Payment Due Tomorrow",
        body = "${first.debtName}$tag$extra: ${MoneyFormatter.formatPeso(first.amountCents)} due tomorrow.",
        highPriority = false
      )
    }

    val upcomingSoon = if (leadDays >= 2L) {
      overview.upcomingDatedPayments.mapNotNull { item ->
        val due = ManilaTime.parseDate(item.dueDateIso) ?: return@mapNotNull null
        val delta = ManilaTime.daysUntil(today, due)
        if (delta in 2L..leadDays) item to delta else null
      }
    } else {
      emptyList()
    }
    if (upcomingSoon.isNotEmpty()) {
      val (first, deltaDays) = upcomingSoon.first()
      val extra = if (upcomingSoon.size > 1) " (+${upcomingSoon.size - 1} more)" else ""
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_UPCOMING,
        title = "Upcoming Payment in $deltaDays Days",
        body = "${first.debtName}$extra: ${MoneyFormatter.formatPeso(first.amountCents)} on ${ManilaTime.formatDisplay(first.dueDateIso)}.",
        highPriority = false
      )
    }

    // Weekly allowance reminder if today matches configured day and no allowance recorded this week
    val weekStart = ManilaTime.weekStart(today)
    val hasAllowanceThisWeek = allowances.any { ManilaTime.isInWeek(it.receivedDate, weekStart) }
    if (!hasAllowanceThisWeek && today.dayOfWeek.value == settings.allowanceReminderDayOfWeek) {
      val minStr = MoneyFormatter.formatPesoCompact(settings.minWeeklyAllowanceCents)
      val maxStr = MoneyFormatter.formatPesoCompact(settings.maxWeeklyAllowanceCents)
      val rangeLabel = if (minStr == maxStr) minStr else "$minStr–$maxStr"
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_ALLOWANCE,
        notificationId = NotificationHelper.NOTIFICATION_ID_ALLOWANCE,
        title = "Weekly Baon Check-In",
        body = "Record this week's allowance ($rangeLabel) to calculate your school food budget and safe debt reserve.",
        highPriority = false
      )
    }

    prefs.setLastReminderEpochMillis(ManilaTime.nowMillis())
    BaonWidgetProvider.requestUpdate(applicationContext)
    return Result.success()
  }
}
