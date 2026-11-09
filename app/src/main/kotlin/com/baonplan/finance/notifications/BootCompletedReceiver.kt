package com.baonplan.finance.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.DefaultSettingsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val db = BaonPlanDatabase.getInstance(context)
        val settings = db.userSettingsDao().getSettings() ?: DefaultSettingsProvider.defaultSettings()
        NotificationHelper.createChannels(context)
        ReminderScheduler.schedulePeriodicReminders(
          context = context,
          enabled = settings.remindersEnabled,
          reminderHour = settings.reminderHour,
          reminderMinute = settings.reminderMinute
        )
      } finally {
        pendingResult.finish()
      }
    }
  }
}
