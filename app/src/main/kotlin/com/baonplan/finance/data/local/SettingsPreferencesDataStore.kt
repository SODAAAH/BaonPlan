package com.baonplan.finance.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baonplan.finance.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.baonPlanPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "baonplan_preferences"
)

data class AppPreferencesState(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val lastBackupEpochMillis: Long = 0L,
  val hasRequestedNotificationPermission: Boolean = false,
  val lastReminderNotificationEpochMillis: Long = 0L
)

class SettingsPreferencesDataStore(private val context: Context) {

  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val LAST_BACKUP_EPOCH = longPreferencesKey("last_backup_epoch")
    val ASKED_NOTIF_PERMISSION = booleanPreferencesKey("asked_notif_permission")
    val LAST_REMINDER_EPOCH = longPreferencesKey("last_reminder_epoch")
  }

  val preferencesFlow: Flow<AppPreferencesState> = context.baonPlanPrefsDataStore.data.map { prefs ->
    val modeName = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
    val themeMode = runCatching { ThemeMode.valueOf(modeName) }.getOrDefault(ThemeMode.SYSTEM)
    AppPreferencesState(
      themeMode = themeMode,
      lastBackupEpochMillis = prefs[Keys.LAST_BACKUP_EPOCH] ?: 0L,
      hasRequestedNotificationPermission = prefs[Keys.ASKED_NOTIF_PERMISSION] ?: false,
      lastReminderNotificationEpochMillis = prefs[Keys.LAST_REMINDER_EPOCH] ?: 0L
    )
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.THEME_MODE] = mode.name
    }
  }

  suspend fun setLastBackupEpochMillis(epochMillis: Long) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.LAST_BACKUP_EPOCH] = epochMillis
    }
  }

  suspend fun markNotificationPermissionRequested() {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.ASKED_NOTIF_PERMISSION] = true
    }
  }

  suspend fun setLastReminderEpochMillis(epochMillis: Long) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.LAST_REMINDER_EPOCH] = epochMillis
    }
  }
}
