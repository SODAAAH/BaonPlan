package com.baonplan.finance.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baonplan.finance.domain.util.ManilaTime
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
  const val PERIODIC_WORK_NAME = "baonplan_daily_reminder_check"
  const val ONE_TIME_WORK_NAME = "baonplan_immediate_reminder_check"

  fun schedulePeriodicReminders(
    context: Context,
    enabled: Boolean,
    reminderHour: Int = 8,
    reminderMinute: Int = 0
  ) {
    val workManager = WorkManager.getInstance(context)
    if (!enabled) {
      workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
      return
    }

    val initialDelayMinutes = computeInitialDelayMinutes(reminderHour, reminderMinute)
    val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
      .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
      .build()

    workManager.enqueueUniquePeriodicWork(
      PERIODIC_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      request
    )
  }

  fun runImmediateCheck(context: Context) {
    val request = OneTimeWorkRequestBuilder<ReminderWorker>().build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      ONE_TIME_WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      request
    )
  }

  internal fun computeInitialDelayMinutes(
    hour: Int,
    minute: Int,
    now: ZonedDateTime = ZonedDateTime.now(ManilaTime.ZONE)
  ): Long {
    val targetTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    var nextRun = now.toLocalDate().atTime(targetTime).atZone(ManilaTime.ZONE)
    if (!nextRun.isAfter(now)) {
      nextRun = nextRun.plusDays(1)
    }
    return Duration.between(now, nextRun).toMinutes().coerceAtLeast(1L)
  }
}
