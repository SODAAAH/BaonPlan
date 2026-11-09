package com.baonplan.finance

import android.app.Application
import androidx.work.WorkManager
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.SettingsPreferencesDataStore
import com.baonplan.finance.data.repository.BaonPlanRepository
import com.baonplan.finance.notifications.NotificationHelper
import com.baonplan.finance.notifications.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BaonPlanApp : Application() {
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val database: BaonPlanDatabase by lazy { BaonPlanDatabase.getInstance(this) }
  val preferencesDataStore: SettingsPreferencesDataStore by lazy { SettingsPreferencesDataStore(this) }
  val repository: BaonPlanRepository by lazy { BaonPlanRepository(database, preferencesDataStore) }

  override fun onCreate() {
    super.onCreate()
    NotificationHelper.createChannels(this)
    appScope.launch {
      repository.ensureInitialized()
      val settings = repository.settingsFlow.first()
      if (WorkManager.isInitialized()) {
        ReminderScheduler.schedulePeriodicReminders(
          context = this@BaonPlanApp,
          enabled = settings.remindersEnabled,
          reminderHour = settings.reminderHour,
          reminderMinute = settings.reminderMinute
        )
      }
    }
  }
}
