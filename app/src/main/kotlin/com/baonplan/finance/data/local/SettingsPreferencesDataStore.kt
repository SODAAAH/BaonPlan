package com.baonplan.finance.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.ThemeMode
import com.baonplan.finance.domain.ocr.LearnedScannerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.baonPlanPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "baonplan_preferences"
)

fun defaultExpensePresets(): List<ExpensePreset> = listOf(
  ExpensePreset(
    id = "meal",
    label = "School meal",
    amountCents = 5_000L,
    category = ExpenseCategory.SCHOOL_FOOD,
    isSchoolDay = true
  ),
  ExpensePreset(
    id = "commute",
    label = "Commute",
    amountCents = 2_000L,
    category = ExpenseCategory.TRANSPORT,
    isSchoolDay = true
  ),
  ExpensePreset(
    id = "supplies",
    label = "Print / supplies",
    amountCents = 2_000L,
    category = ExpenseCategory.SCHOOL_SUPPLIES,
    isSchoolDay = false
  )
)

data class AppPreferencesState(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val lastBackupEpochMillis: Long = 0L,
  val hasRequestedNotificationPermission: Boolean = false,
  val lastReminderNotificationEpochMillis: Long = 0L,
  val reminderLeadDays: Int = 3,
  val emergencySavingsGoalCents: Long = 50_000L,
  val lastResolvedCarryOverWeekIso: String? = null,
  val appLockEnabled: Boolean = false,
  val expensePresets: List<ExpensePreset> = defaultExpensePresets(),
  val learnedScannerPreferences: LearnedScannerPreferences = LearnedScannerPreferences()
)

class SettingsPreferencesDataStore(private val context: Context) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val presetListSerializer = ListSerializer(ExpensePreset.serializer())

  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val LAST_BACKUP_EPOCH = longPreferencesKey("last_backup_epoch")
    val ASKED_NOTIF_PERMISSION = booleanPreferencesKey("asked_notif_permission")
    val LAST_REMINDER_EPOCH = longPreferencesKey("last_reminder_epoch")
    val REMINDER_LEAD_DAYS = intPreferencesKey("reminder_lead_days")
    val EMERGENCY_GOAL_CENTS = longPreferencesKey("emergency_goal_cents")
    val RESOLVED_CARRYOVER_WEEK = stringPreferencesKey("resolved_carryover_week")
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    val EXPENSE_PRESETS_JSON = stringPreferencesKey("expense_presets_json")
    val LEARNED_SCANNER_PREFS_JSON = stringPreferencesKey("learned_scanner_prefs_json")
  }

  val preferencesFlow: Flow<AppPreferencesState> = context.baonPlanPrefsDataStore.data.map { prefs ->
    val modeName = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
    val themeMode = runCatching { ThemeMode.valueOf(modeName) }.getOrDefault(ThemeMode.SYSTEM)
    val rawPresets = prefs[Keys.EXPENSE_PRESETS_JSON]
    val presets = if (rawPresets.isNullOrBlank()) {
      defaultExpensePresets()
    } else {
      runCatching { json.decodeFromString(presetListSerializer, rawPresets) }
        .getOrDefault(defaultExpensePresets())
    }
    val rawLearned = prefs[Keys.LEARNED_SCANNER_PREFS_JSON]
    val learnedScanner = if (rawLearned.isNullOrBlank()) {
      LearnedScannerPreferences()
    } else {
      runCatching { json.decodeFromString(LearnedScannerPreferences.serializer(), rawLearned) }
        .getOrDefault(LearnedScannerPreferences())
    }
    AppPreferencesState(
      themeMode = themeMode,
      lastBackupEpochMillis = prefs[Keys.LAST_BACKUP_EPOCH] ?: 0L,
      hasRequestedNotificationPermission = prefs[Keys.ASKED_NOTIF_PERMISSION] ?: false,
      lastReminderNotificationEpochMillis = prefs[Keys.LAST_REMINDER_EPOCH] ?: 0L,
      reminderLeadDays = (prefs[Keys.REMINDER_LEAD_DAYS] ?: 3).coerceIn(1, 7),
      emergencySavingsGoalCents = (prefs[Keys.EMERGENCY_GOAL_CENTS] ?: 50_000L).coerceAtLeast(0L),
      lastResolvedCarryOverWeekIso = prefs[Keys.RESOLVED_CARRYOVER_WEEK],
      appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
      expensePresets = presets,
      learnedScannerPreferences = learnedScanner
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

  suspend fun setReminderLeadDays(days: Int) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.REMINDER_LEAD_DAYS] = days.coerceIn(1, 7)
    }
  }

  suspend fun setEmergencySavingsGoalCents(goalCents: Long) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.EMERGENCY_GOAL_CENTS] = goalCents.coerceAtLeast(0L)
    }
  }

  suspend fun setLastResolvedCarryOverWeekIso(weekIso: String?) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      if (weekIso == null) {
        prefs.remove(Keys.RESOLVED_CARRYOVER_WEEK)
      } else {
        prefs[Keys.RESOLVED_CARRYOVER_WEEK] = weekIso
      }
    }
  }

  suspend fun setAppLockEnabled(enabled: Boolean) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.APP_LOCK_ENABLED] = enabled
    }
  }

  suspend fun setExpensePresets(presets: List<ExpensePreset>) {
    val clean = presets.take(6)
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.EXPENSE_PRESETS_JSON] = json.encodeToString(presetListSerializer, clean)
    }
  }

  suspend fun setLearnedScannerPreferences(learned: LearnedScannerPreferences) {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs[Keys.LEARNED_SCANNER_PREFS_JSON] = json.encodeToString(LearnedScannerPreferences.serializer(), learned)
    }
  }

  suspend fun clearOperationalPrefs() {
    context.baonPlanPrefsDataStore.edit { prefs ->
      prefs.remove(Keys.RESOLVED_CARRYOVER_WEEK)
      prefs.remove(Keys.LEARNED_SCANNER_PREFS_JSON)
    }
  }
}
