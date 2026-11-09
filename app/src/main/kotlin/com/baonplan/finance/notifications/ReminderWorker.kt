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

class ReminderWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val db = BaonPlanDatabase.getInstance(applicationContext)
    val prefs = SettingsPreferencesDataStore(applicationContext)
    val settings = db.userSettingsDao().getSettings() ?: DefaultSettingsProvider.defaultSettings()

    if (!settings.remindersEnabled) {
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
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_OVERDUE,
        title = "Overdue Payment Alert (${overview.overduePayments.size})",
        body = "${first.debtName}$tag · ${MoneyFormatter.formatPeso(totalOverdue)} overdue. Open Calendar to review or confirm.",
        highPriority = true
      )
    }

    if (overview.dueTodayPayments.isNotEmpty()) {
      val first = overview.dueTodayPayments.first()
      val tag = if (first.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION) " (Estimated)" else ""
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_DUE_TODAY,
        title = "Payment Due Today",
        body = "${first.debtName}$tag: ${MoneyFormatter.formatPeso(first.amountCents)} due today (${ManilaTime.formatDisplay(today)}).",
        highPriority = true
      )
    }

    if (overview.dueTomorrowPayments.isNotEmpty()) {
      val first = overview.dueTomorrowPayments.first()
      val tag = if (first.dateStatus == DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION) " (Estimated)" else ""
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_DUE_TOMORROW,
        title = "Payment Due Tomorrow",
        body = "${first.debtName}$tag: ${MoneyFormatter.formatPeso(first.amountCents)} due tomorrow.",
        highPriority = false
      )
    }

    val upcomingSoon = overview.upcomingDatedPayments.mapNotNull { item ->
      val due = ManilaTime.parseDate(item.dueDateIso) ?: return@mapNotNull null
      val delta = ManilaTime.daysUntil(today, due)
      if (delta in 2L..3L) item to delta else null
    }
    if (upcomingSoon.isNotEmpty()) {
      val (first, deltaDays) = upcomingSoon.first()
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_PAYMENTS,
        notificationId = NotificationHelper.NOTIFICATION_ID_UPCOMING,
        title = "Upcoming Payment in $deltaDays Days",
        body = "${first.debtName}: ${MoneyFormatter.formatPeso(first.amountCents)} on ${ManilaTime.formatDisplay(first.dueDateIso)}.",
        highPriority = false
      )
    }

    // Weekly allowance reminder if today matches configured day and no allowance recorded this week
    val weekStart = ManilaTime.weekStart(today)
    val hasAllowanceThisWeek = allowances.any { ManilaTime.isInWeek(it.receivedDate, weekStart) }
    if (!hasAllowanceThisWeek && today.dayOfWeek.value == settings.allowanceReminderDayOfWeek) {
      NotificationHelper.showNotification(
        context = applicationContext,
        channelId = NotificationHelper.CHANNEL_ALLOWANCE,
        notificationId = NotificationHelper.NOTIFICATION_ID_ALLOWANCE,
        title = "Weekly Baon Check-In",
        body = "Record this week's allowance (₱400–₱500) to calculate your school food budget and safe debt reserve.",
        highPriority = false
      )
    }

    prefs.setLastReminderEpochMillis(ManilaTime.nowMillis())
    return Result.success()
  }
}
