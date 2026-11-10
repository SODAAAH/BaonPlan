package com.baonplan.finance.data.repository

import androidx.room.withTransaction
import com.baonplan.finance.data.backup.BaonPlanBackupPayload
import com.baonplan.finance.data.ocr.ImageScanPreprocessor
import com.baonplan.finance.data.local.AppPreferencesState
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.SettingsPreferencesDataStore
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.BudgetAllocationEntity
import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.IncomeOccurrenceEntity
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.budget.BudgetRecurrenceEngine
import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ThemeMode
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.ocr.LearnedScannerPreferences
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class RetainedAttachmentSource(
  val sourceFile: File,
  val displayLabel: String,
  val screenshotIndex: Int,
  val rotationDegrees: Int = 0,
  val isPdf: Boolean = false
)

class BaonPlanRepository(
  private val db: BaonPlanDatabase,
  private val prefs: SettingsPreferencesDataStore,
  private val attachmentStorageDir: File? = null
) {
  private val debtDao = db.debtDao()
  private val installmentDao = db.installmentDao()
  private val transactionDao = db.transactionDao()
  private val allowanceDao = db.allowanceRecordDao()
  private val expenseDao = db.expenseDao()
  private val reserveDao = db.debtReserveDao()
  private val settingsDao = db.userSettingsDao()
  private val attachmentDao = db.debtAttachmentDao()
  private val budgetProfileDao = db.budgetProfileDao()
  private val incomeSourceDao = db.incomeSourceDao()
  private val incomeOccurrenceDao = db.incomeOccurrenceDao()
  private val budgetAllocationDao = db.budgetAllocationDao()

  val debtsFlow: Flow<List<DebtEntity>> = debtDao.observeAll()
  val installmentsFlow: Flow<List<InstallmentEntity>> = installmentDao.observeAll()
  val attachmentsFlow: Flow<List<DebtAttachmentEntity>> = attachmentDao.observeAll()
  val transactionsFlow: Flow<List<TransactionEntity>> = transactionDao.observeAll()
  val allowancesFlow: Flow<List<AllowanceRecordEntity>> = allowanceDao.observeAll()
  val expensesFlow: Flow<List<ExpenseEntity>> = expenseDao.observeAll()
  val reservesFlow: Flow<List<DebtReserveEntity>> = reserveDao.observeAll()
  val settingsFlow: Flow<UserSettingsEntity> = settingsDao.observeSettings().map {
    it ?: DefaultSettingsProvider.defaultSettings()
  }
  val budgetProfileFlow: Flow<BudgetProfileEntity> = budgetProfileDao.observeProfile().map {
    it ?: BudgetProfileEntity()
  }
  val incomeSourcesFlow: Flow<List<IncomeSourceEntity>> = incomeSourceDao.observeAll()
  val incomeOccurrencesFlow: Flow<List<IncomeOccurrenceEntity>> = incomeOccurrenceDao.observeAll()
  val budgetAllocationsFlow: Flow<List<BudgetAllocationEntity>> = budgetAllocationDao.observeAll()
  val appPreferencesFlow: Flow<AppPreferencesState> = prefs.preferencesFlow

  fun observeDebt(debtId: Long): Flow<DebtEntity?> = debtDao.observeById(debtId)
  fun observeDebtInstallments(debtId: Long): Flow<List<InstallmentEntity>> =
    installmentDao.observeByDebtId(debtId)
  fun observeDebtAttachments(debtId: Long): Flow<List<DebtAttachmentEntity>> =
    attachmentDao.observeByDebtId(debtId)
  fun observeDebtTransactions(debtId: Long): Flow<List<TransactionEntity>> =
    transactionDao.observeByDebtId(debtId)

  fun resolveAttachmentFile(fileName: String): File? {
    val dir = attachmentStorageDir ?: return null
    val safeName = File(fileName).name
    if (safeName.isBlank()) return null
    return File(dir, safeName)
  }

  suspend fun ensureInitialized() {
    if (settingsDao.getSettings() == null) {
      settingsDao.upsert(DefaultSettingsProvider.defaultSettings())
    }
  }

  suspend fun clearAllData() {
    db.withTransaction {
      attachmentDao.deleteAll()
      installmentDao.deleteAll()
      transactionDao.deleteAll()
      allowanceDao.deleteAll()
      expenseDao.deleteAll()
      reserveDao.deleteAll()
      incomeOccurrenceDao.deleteAll()
      incomeSourceDao.deleteAll()
      budgetAllocationDao.deleteAll()
      budgetProfileDao.deleteAll()
      debtDao.deleteAll()
      settingsDao.upsert(DefaultSettingsProvider.defaultSettings())
    }
    deleteAllStoredAttachments()
    prefs.clearOperationalPrefs()
  }

  suspend fun updateSettings(settings: UserSettingsEntity) {
    settingsDao.upsert(settings.copy(id = 1))
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    prefs.setThemeMode(mode)
  }

  suspend fun setReminderLeadDays(days: Int) {
    prefs.setReminderLeadDays(days)
  }

  suspend fun setEmergencySavingsGoalCents(goalCents: Long) {
    prefs.setEmergencySavingsGoalCents(goalCents)
  }

  suspend fun setAppLockEnabled(enabled: Boolean) {
    prefs.setAppLockEnabled(enabled)
  }

  suspend fun setExpensePresets(presets: List<ExpensePreset>) {
    prefs.setExpensePresets(presets)
  }

  suspend fun setLearnedScannerPreferences(updated: LearnedScannerPreferences) {
    prefs.setLearnedScannerPreferences(updated)
  }

  suspend fun carryOverPriorWeekCash(prevWeekStartIso: String, amountCents: Long, todayIso: String) {
    if (amountCents > 0L) {
      recordAllowance(
        amountCents = amountCents,
        receivedDateIso = todayIso,
        sourceLabel = "Carried from last week",
        notes = "Unspent balance from week of $prevWeekStartIso"
      )
    }
    prefs.setLastResolvedCarryOverWeekIso(prevWeekStartIso)
  }

  suspend fun sweepPriorWeekCashToReserve(
    prevWeekStartIso: String,
    reserveType: ReserveType,
    amountCents: Long
  ) {
    val prevStart = ManilaTime.parseDate(prevWeekStartIso) ?: return
    if (amountCents > 0L) {
      val prevEndIso = ManilaTime.formatIso(prevStart.plusDays(6))
      val now = ManilaTime.nowMillis()
      val title = if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE) {
        "Debt Reserve (Last Week)"
      } else {
        "Emergency Savings (Last Week)"
      }
      db.withTransaction {
        val reserveId = reserveDao.insert(
          DebtReserveEntity(
            weekStartDate = prevWeekStartIso,
            targetDebtId = null,
            reserveType = reserveType,
            amountCents = amountCents,
            appliedAmountCents = 0L,
            status = ReserveStatus.RESERVED,
            notes = "Swept unspent cash from week of $prevWeekStartIso",
            isManuallyEntered = true,
            createdAtEpochMillis = now
          )
        )
        transactionDao.insert(
          TransactionEntity(
            type = if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE) {
              TransactionType.DEBT_RESERVE
            } else {
              TransactionType.EMERGENCY_SAVINGS
            },
            title = title,
            amountCents = amountCents,
            date = prevEndIso,
            reserveId = reserveId,
            category = reserveType.label,
            notes = "Swept unspent cash from week of $prevWeekStartIso",
            isManuallyEntered = true,
            createdAtEpochMillis = now
          )
        )
      }
    }
    prefs.setLastResolvedCarryOverWeekIso(prevWeekStartIso)
  }

  suspend fun dismissPriorWeekCarryOver(prevWeekStartIso: String) {
    prefs.setLastResolvedCarryOverWeekIso(prevWeekStartIso)
  }

  suspend fun markNotificationPermissionRequested() {
    prefs.markNotificationPermissionRequested()
  }

  suspend fun recordAllowance(
    amountCents: Long,
    receivedDateIso: String,
    sourceLabel: String = "Weekly Allowance",
    notes: String = ""
  ) {
    require(amountCents > 0L) { "Allowance amount must be positive." }
    val date = ManilaTime.parseDate(receivedDateIso) ?: ManilaTime.today()
    val weekStartIso = ManilaTime.formatIso(ManilaTime.weekStart(date))
    val now = ManilaTime.nowMillis()
    db.withTransaction {
      val allowanceId = allowanceDao.insert(
        AllowanceRecordEntity(
          weekStartDate = weekStartIso,
          receivedDate = ManilaTime.formatIso(date),
          amountCents = amountCents,
          sourceLabel = sourceLabel.ifBlank { "Weekly Allowance" },
          notes = notes,
          isManuallyEntered = true,
          createdAtEpochMillis = now
        )
      )
      transactionDao.insert(
        TransactionEntity(
          type = TransactionType.ALLOWANCE,
          title = sourceLabel.ifBlank { "Weekly Allowance" },
          amountCents = amountCents,
          date = ManilaTime.formatIso(date),
          allowanceId = allowanceId,
          category = "Allowance",
          notes = notes,
          isManuallyEntered = true,
          createdAtEpochMillis = now
        )
      )
    }
  }

  suspend fun deleteAllowance(allowanceId: Long) {
    db.withTransaction {
      val existingRecord = allowanceDao.getById(allowanceId)
      allowanceDao.deleteById(allowanceId)
      val linkedTx = transactionDao.getAll().firstOrNull { it.allowanceId == allowanceId && !it.isVoided }
      if (linkedTx != null) {
        transactionDao.update(
          linkedTx.copy(isVoided = true, isCorrected = true, correctionReason = "Allowance record removed")
        )
      }
      val linkedOcc = existingRecord?.incomeOccurrenceId?.let { incomeOccurrenceDao.getById(it) }
        ?: incomeOccurrenceDao.findByLinkedAllowanceId(allowanceId)
      if (linkedOcc != null) {
        incomeOccurrenceDao.update(
          linkedOcc.copy(
            status = IncomeOccurrenceStatus.EXPECTED,
            actualReceivedAmountCents = 0L,
            actualReceivedDateIso = null,
            linkedAllowanceRecordId = null,
            updatedAtEpochMillis = ManilaTime.nowMillis()
          )
        )
      }
    }
  }

  /**
   * Initializes or updates the adaptive Budget Overview in a single atomic transaction.
   * Never creates fake AllowanceRecord/Transaction cash entries for unreceived future income.
   */
  suspend fun setupBudgetWithPrimaryIncome(
    source: IncomeSourceEntity,
    profile: BudgetProfileEntity,
    markFirstOccurrenceReceivedToday: Boolean = false
  ): Long {
    require(source.expectedAmountCents > 0L) { "Allowance amount must be positive." }
    val startDate = ManilaTime.parseDate(source.startDateIso)
      ?: throw IllegalArgumentException("Valid allowance start date is required.")
    val cleanName = source.name.trim().ifBlank { "Main Allowance" }
    val cleanAnchorDay = source.anchorDayOfMonth ?: startDate.dayOfMonth
    val now = ManilaTime.nowMillis()

    return db.withTransaction {
      val existingSources = incomeSourceDao.getAll()
      for (other in existingSources) {
        if (other.isPrimary) {
          incomeSourceDao.update(other.copy(isPrimary = false, updatedAtEpochMillis = now))
        }
      }
      val newSourceId = incomeSourceDao.insert(
        source.copy(
          id = 0L,
          name = cleanName,
          anchorDayOfMonth = cleanAnchorDay,
          isActive = true,
          isPaused = false,
          pausedFromDateIso = null,
          isPrimary = true,
          createdAtEpochMillis = now,
          updatedAtEpochMillis = now
        )
      )
      val existingProfile = budgetProfileDao.getProfile() ?: BudgetProfileEntity()
      budgetProfileDao.upsert(
        profile.copy(
          id = 1,
          isConfigured = true,
          primarySourceId = newSourceId,
          updatedAtEpochMillis = now
        )
      )
      if (markFirstOccurrenceReceivedToday) {
        confirmOrUpdateIncomeOccurrenceInternal(
          occurrenceId = null,
          sourceId = newSourceId,
          expectedDateIso = ManilaTime.formatIso(startDate),
          expectedAmountCents = source.expectedAmountCents,
          status = IncomeOccurrenceStatus.RECEIVED,
          actualAmountCents = source.expectedAmountCents,
          actualDateIso = ManilaTime.formatIso(startDate),
          notes = "Initial allowance receipt"
        )
      } else if (existingProfile.id == 1 && !existingProfile.isConfigured) {
        budgetProfileDao.upsert(
          profile.copy(
            id = 1,
            isConfigured = true,
            primarySourceId = newSourceId,
            updatedAtEpochMillis = now
          )
        )
      }
      newSourceId
    }
  }

  suspend fun saveBudgetProfile(profile: BudgetProfileEntity) {
    budgetProfileDao.upsert(
      profile.copy(
        id = 1,
        updatedAtEpochMillis = ManilaTime.nowMillis()
      )
    )
  }

  /**
   * Creates or edits an [IncomeSourceEntity] cleanly.
   * When editing an existing source from [effectiveFromDateIso], freezes prior unconfirmed expected
   * occurrences (`< effectiveFromDateIso`) in `income_occurrences` so historical amounts and dates
   * never silently shift or rewrite past periods.
   */
  suspend fun saveIncomeSource(
    source: IncomeSourceEntity,
    effectiveFromDateIso: String? = null
  ): Long {
    require(source.expectedAmountCents > 0L) { "Income amount must be positive." }
    val parsedStart = ManilaTime.parseDate(source.startDateIso)
      ?: throw IllegalArgumentException("Valid start date is required.")
    val cleanName = source.name.trim().ifBlank { "Income Source" }
    val cleanAnchorDay = source.anchorDayOfMonth ?: parsedStart.dayOfMonth
    val now = ManilaTime.nowMillis()

    return db.withTransaction {
      if (source.isPrimary) {
        for (other in incomeSourceDao.getAll()) {
          if (other.id != source.id && other.isPrimary) {
            incomeSourceDao.update(other.copy(isPrimary = false, updatedAtEpochMillis = now))
          }
        }
      }

      val finalId = if (source.id == 0L) {
        val insertedId = incomeSourceDao.insert(
          source.copy(
            name = cleanName,
            anchorDayOfMonth = cleanAnchorDay,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
          )
        )
        val profile = budgetProfileDao.getProfile() ?: BudgetProfileEntity()
        if (!profile.isConfigured || source.isPrimary || profile.primarySourceId == null) {
          budgetProfileDao.upsert(
            profile.copy(
              id = 1,
              isConfigured = true,
              primarySourceId = if (source.isPrimary || profile.primarySourceId == null) insertedId else profile.primarySourceId,
              updatedAtEpochMillis = now
            )
          )
        }
        insertedId
      } else {
        val existing = incomeSourceDao.getById(source.id)
        val cutoffDate = effectiveFromDateIso?.let { ManilaTime.parseDate(it) }
          ?: ManilaTime.today()
        val cutoffIso = ManilaTime.formatIso(cutoffDate)

        if (existing != null && existing.startDateIso < cutoffIso) {
          val oldStart = ManilaTime.parseDate(existing.effectiveFromDateIso ?: existing.startDateIso)
            ?: ManilaTime.parseDate(existing.startDateIso)
          if (oldStart != null && oldStart.isBefore(cutoffDate)) {
            val historicalDates = BudgetRecurrenceEngine.generateScheduledDates(
              source = existing,
              windowStart = oldStart,
              windowEndExclusive = cutoffDate,
              ignorePausedState = true
            )
            for (histDate in historicalDates) {
              val histIso = ManilaTime.formatIso(histDate)
              val alreadySaved = incomeOccurrenceDao.findBySourceAndExpectedDate(existing.id, histIso)
              if (alreadySaved == null) {
                incomeOccurrenceDao.insert(
                  IncomeOccurrenceEntity(
                    sourceId = existing.id,
                    sourceNameSnapshot = existing.name,
                    expectedDateIso = histIso,
                    expectedAmountCents = existing.expectedAmountCents,
                    status = IncomeOccurrenceStatus.EXPECTED,
                    isFrozenScheduleHistory = true,
                    notes = "Preserved prior schedule",
                    updatedAtEpochMillis = now
                  )
                )
              } else if (!alreadySaved.isFrozenScheduleHistory && alreadySaved.status == IncomeOccurrenceStatus.EXPECTED) {
                incomeOccurrenceDao.update(
                  alreadySaved.copy(
                    isFrozenScheduleHistory = true,
                    updatedAtEpochMillis = now
                  )
                )
              }
            }
          }
          // Replace only unconfirmed future occurrences >= cutoffIso
          incomeOccurrenceDao.deleteFutureExpectedBySource(existing.id, cutoffIso)
        }

        val effectiveCutoffToPersist = if (existing != null &&
          (existing.expectedAmountCents != source.expectedAmountCents ||
            existing.frequency != source.frequency ||
            existing.startDateIso != source.startDateIso ||
            existing.customIntervalDays != source.customIntervalDays ||
            existing.customIntervalMonths != source.customIntervalMonths ||
            existing.twiceMonthlyPattern != source.twiceMonthlyPattern ||
            existing.twiceMonthlyDay1 != source.twiceMonthlyDay1 ||
            existing.twiceMonthlyDay2 != source.twiceMonthlyDay2 ||
            existing.customDatesCsv != source.customDatesCsv)
        ) {
          cutoffIso
        } else {
          source.effectiveFromDateIso
        }

        incomeSourceDao.update(
          source.copy(
            name = cleanName,
            anchorDayOfMonth = cleanAnchorDay,
            effectiveFromDateIso = effectiveCutoffToPersist,
            updatedAtEpochMillis = now
          )
        )
        if (source.isPrimary) {
          val profile = budgetProfileDao.getProfile() ?: BudgetProfileEntity()
          budgetProfileDao.upsert(
            profile.copy(
              id = 1,
              isConfigured = true,
              primarySourceId = source.id,
              updatedAtEpochMillis = now
            )
          )
        }
        source.id
      }
      finalId
    }
  }

  suspend fun setIncomeSourcePaused(sourceId: Long, paused: Boolean, effectiveDateIso: String) {
    db.withTransaction {
      val src = incomeSourceDao.getById(sourceId) ?: return@withTransaction
      val now = ManilaTime.nowMillis()
      if (paused) {
        val cutoff = ManilaTime.parseDate(effectiveDateIso) ?: ManilaTime.today()
        val oldStart = ManilaTime.parseDate(src.effectiveFromDateIso ?: src.startDateIso)
        if (oldStart != null && oldStart.isBefore(cutoff)) {
          val priorDates = BudgetRecurrenceEngine.generateScheduledDates(src, oldStart, cutoff)
          for (d in priorDates) {
            val dIso = ManilaTime.formatIso(d)
            if (incomeOccurrenceDao.findBySourceAndExpectedDate(src.id, dIso) == null) {
              incomeOccurrenceDao.insert(
                IncomeOccurrenceEntity(
                  sourceId = src.id,
                  sourceNameSnapshot = src.name,
                  expectedDateIso = dIso,
                  expectedAmountCents = src.expectedAmountCents,
                  status = IncomeOccurrenceStatus.EXPECTED,
                  isFrozenScheduleHistory = true,
                  notes = "Preserved prior schedule",
                  updatedAtEpochMillis = now
                )
              )
            }
          }
        }
        incomeOccurrenceDao.deleteFutureExpectedBySource(src.id, ManilaTime.formatIso(cutoff))
        incomeSourceDao.update(
          src.copy(
            isPaused = true,
            pausedFromDateIso = ManilaTime.formatIso(cutoff),
            updatedAtEpochMillis = now
          )
        )
      } else {
        incomeSourceDao.update(
          src.copy(
            isPaused = false,
            pausedFromDateIso = null,
            effectiveFromDateIso = effectiveDateIso,
            updatedAtEpochMillis = now
          )
        )
      }
    }
  }

  suspend fun endIncomeSource(sourceId: Long, endDateIso: String) {
    db.withTransaction {
      val src = incomeSourceDao.getById(sourceId) ?: return@withTransaction
      val endDate = ManilaTime.parseDate(endDateIso) ?: ManilaTime.today()
      val now = ManilaTime.nowMillis()
      val nextDayIso = ManilaTime.formatIso(endDate.plusDays(1))
      // Freeze dates up to and including endDate so past expectations stay accurate
      val start = ManilaTime.parseDate(src.effectiveFromDateIso ?: src.startDateIso)
      if (start != null && !start.isAfter(endDate)) {
        val validDates = BudgetRecurrenceEngine.generateScheduledDates(
          source = src,
          windowStart = start,
          windowEndExclusive = endDate.plusDays(1)
        )
        for (d in validDates) {
          val iso = ManilaTime.formatIso(d)
          if (incomeOccurrenceDao.findBySourceAndExpectedDate(src.id, iso) == null) {
            incomeOccurrenceDao.insert(
              IncomeOccurrenceEntity(
                sourceId = src.id,
                sourceNameSnapshot = src.name,
                expectedDateIso = iso,
                expectedAmountCents = src.expectedAmountCents,
                status = IncomeOccurrenceStatus.EXPECTED,
                isFrozenScheduleHistory = true,
                notes = "Preserved prior schedule",
                updatedAtEpochMillis = now
              )
            )
          }
        }
      }
      incomeOccurrenceDao.deleteFutureExpectedBySource(src.id, nextDayIso)
      incomeSourceDao.update(
        src.copy(
          endDateIso = ManilaTime.formatIso(endDate),
          isActive = false,
          isPrimary = false,
          updatedAtEpochMillis = now
        )
      )
      val profile = budgetProfileDao.getProfile()
      if (profile != null && profile.primarySourceId == sourceId) {
        val nextActive = incomeSourceDao.getAll().firstOrNull { it.id != sourceId && it.isActive }
        budgetProfileDao.upsert(
          profile.copy(
            primarySourceId = nextActive?.id,
            updatedAtEpochMillis = now
          )
        )
      }
    }
  }

  /**
   * Confirms, partially confirms, misses, cancels, or edits an expected/past income occurrence
   * atomically while maintaining strict 1-to-1 synchronization with ONE [AllowanceRecordEntity]
   * and ONE [TransactionEntity] (zero double-counting).
   */
  suspend fun confirmOrUpdateIncomeOccurrence(
    occurrenceId: Long?,
    sourceId: Long?,
    expectedDateIso: String,
    expectedAmountCents: Long,
    status: IncomeOccurrenceStatus,
    actualAmountCents: Long,
    actualDateIso: String?,
    notes: String = ""
  ): Long {
    return db.withTransaction {
      confirmOrUpdateIncomeOccurrenceInternal(
        occurrenceId = occurrenceId,
        sourceId = sourceId,
        expectedDateIso = expectedDateIso,
        expectedAmountCents = expectedAmountCents,
        status = status,
        actualAmountCents = actualAmountCents,
        actualDateIso = actualDateIso,
        notes = notes
      )
    }
  }

  private suspend fun confirmOrUpdateIncomeOccurrenceInternal(
    occurrenceId: Long?,
    sourceId: Long?,
    expectedDateIso: String,
    expectedAmountCents: Long,
    status: IncomeOccurrenceStatus,
    actualAmountCents: Long,
    actualDateIso: String?,
    notes: String
  ): Long {
    val now = ManilaTime.nowMillis()
    val src = sourceId?.let { incomeSourceDao.getById(it) }
    val existing = occurrenceId?.let { incomeOccurrenceDao.getById(it) }
      ?: sourceId?.let { incomeOccurrenceDao.findBySourceAndExpectedDate(it, expectedDateIso) }

    val sourceName = src?.name ?: existing?.sourceNameSnapshot?.ifBlank { null } ?: "Allowance"

    val finalStatus = if (status.isSettledCashIn) {
      require(actualAmountCents > 0L) { "Received allowance amount must be positive." }
      if (actualAmountCents < expectedAmountCents && status == IncomeOccurrenceStatus.RECEIVED) {
        status // user explicitly kept RECEIVED or caller selected PARTIALLY_RECEIVED
      } else {
        status
      }
    } else {
      status
    }

    val safeExpectedAmount = existing?.expectedAmountCents ?: expectedAmountCents.coerceAtLeast(0L)
    val baseOcc = existing?.copy(
      sourceNameSnapshot = sourceName,
      expectedAmountCents = safeExpectedAmount,
      status = finalStatus,
      actualReceivedAmountCents = if (finalStatus.isSettledCashIn) actualAmountCents else 0L,
      actualReceivedDateIso = if (finalStatus.isSettledCashIn) (actualDateIso ?: expectedDateIso) else null,
      isFrozenScheduleHistory = true,
      notes = notes,
      updatedAtEpochMillis = now
    ) ?: IncomeOccurrenceEntity(
      sourceId = sourceId,
      sourceNameSnapshot = sourceName,
      expectedDateIso = expectedDateIso,
      expectedAmountCents = safeExpectedAmount,
      actualReceivedAmountCents = if (finalStatus.isSettledCashIn) actualAmountCents else 0L,
      actualReceivedDateIso = if (finalStatus.isSettledCashIn) (actualDateIso ?: expectedDateIso) else null,
      status = finalStatus,
      isFrozenScheduleHistory = true,
      notes = notes,
      updatedAtEpochMillis = now
    )

    val occId = if (existing == null) {
      incomeOccurrenceDao.insert(baseOcc)
    } else {
      incomeOccurrenceDao.update(baseOcc)
      existing.id
    }

    val existingLinkedRecord = existing?.linkedAllowanceRecordId?.let { allowanceDao.getById(it) }
      ?: allowanceDao.findByIncomeOccurrenceId(occId)

    if (finalStatus.isSettledCashIn) {
      val settledDateStr = actualDateIso?.takeIf { ManilaTime.parseDate(it) != null } ?: expectedDateIso
      val settledLocalDate = ManilaTime.parseDate(settledDateStr) ?: ManilaTime.today()
      val weekStartIso = ManilaTime.formatIso(ManilaTime.weekStart(settledLocalDate))

      val recordId = if (existingLinkedRecord == null) {
        val insertedRecId = allowanceDao.insert(
          AllowanceRecordEntity(
            weekStartDate = weekStartIso,
            receivedDate = settledDateStr,
            amountCents = actualAmountCents,
            sourceLabel = sourceName,
            notes = notes,
            isManuallyEntered = true,
            incomeOccurrenceId = occId,
            createdAtEpochMillis = now
          )
        )
        transactionDao.insert(
          TransactionEntity(
            type = TransactionType.ALLOWANCE,
            title = sourceName,
            amountCents = actualAmountCents,
            date = settledDateStr,
            allowanceId = insertedRecId,
            category = "Allowance",
            notes = notes,
            isManuallyEntered = true,
            createdAtEpochMillis = now
          )
        )
        insertedRecId
      } else {
        allowanceDao.update(
          existingLinkedRecord.copy(
            weekStartDate = weekStartIso,
            receivedDate = settledDateStr,
            amountCents = actualAmountCents,
            sourceLabel = sourceName,
            notes = notes,
            incomeOccurrenceId = occId
          )
        )
        val activeTx = transactionDao.findActiveByAllowanceId(existingLinkedRecord.id)
        if (activeTx != null) {
          transactionDao.update(
            activeTx.copy(
              title = sourceName,
              amountCents = actualAmountCents,
              date = settledDateStr,
              notes = notes,
              isCorrected = activeTx.amountCents != actualAmountCents || activeTx.date != settledDateStr,
              correctionReason = if (activeTx.amountCents != actualAmountCents || activeTx.date != settledDateStr) {
                "Updated allowance receipt"
              } else activeTx.correctionReason
            )
          )
        } else {
          transactionDao.insert(
            TransactionEntity(
              type = TransactionType.ALLOWANCE,
              title = sourceName,
              amountCents = actualAmountCents,
              date = settledDateStr,
              allowanceId = existingLinkedRecord.id,
              category = "Allowance",
              notes = notes,
              isManuallyEntered = true,
              createdAtEpochMillis = now
            )
          )
        }
        existingLinkedRecord.id
      }

      val persistedOcc = incomeOccurrenceDao.getById(occId)
      if (persistedOcc != null && persistedOcc.linkedAllowanceRecordId != recordId) {
        incomeOccurrenceDao.update(persistedOcc.copy(linkedAllowanceRecordId = recordId))
      }
    } else if (existingLinkedRecord != null) {
      val activeTx = transactionDao.findActiveByAllowanceId(existingLinkedRecord.id)
      if (activeTx != null) {
        transactionDao.update(
          activeTx.copy(
            isVoided = true,
            isCorrected = true,
            correctionReason = "Occurrence marked ${finalStatus.label.lowercase()}"
          )
        )
      }
      allowanceDao.deleteById(existingLinkedRecord.id)
      val persistedOcc = incomeOccurrenceDao.getById(occId)
      if (persistedOcc != null && persistedOcc.linkedAllowanceRecordId != null) {
        incomeOccurrenceDao.update(persistedOcc.copy(linkedAllowanceRecordId = null))
      }
    }

    return occId
  }

  suspend fun upsertBudgetAllocation(categoryKey: BudgetCategoryKey, amountCents: Long, enabled: Boolean) {
    require(amountCents >= 0L) { "Allocation cannot be negative." }
    db.withTransaction {
      val existing = budgetAllocationDao.getByCategoryKey(categoryKey)
      val now = ManilaTime.nowMillis()
      if (existing == null) {
        budgetAllocationDao.insert(
          BudgetAllocationEntity(
            categoryKey = categoryKey,
            allocatedAmountCents = amountCents,
            isEnabled = enabled && amountCents > 0L,
            updatedAtEpochMillis = now
          )
        )
      } else {
        budgetAllocationDao.update(
          existing.copy(
            allocatedAmountCents = amountCents,
            isEnabled = enabled && amountCents > 0L,
            updatedAtEpochMillis = now
          )
        )
      }
    }
  }

  suspend fun deleteBudgetAllocation(categoryKey: BudgetCategoryKey) {
    budgetAllocationDao.deleteByCategoryKey(categoryKey)
  }

  suspend fun recordExpense(
    title: String,
    amountCents: Long,
    dateIso: String,
    category: ExpenseCategory,
    isSchoolDay: Boolean,
    notes: String = ""
  ) {
    require(amountCents > 0L) { "Expense amount must be positive." }
    val now = ManilaTime.nowMillis()
    val cleanTitle = title.ifBlank { category.label }
    db.withTransaction {
      val expenseId = expenseDao.insert(
        ExpenseEntity(
          date = dateIso,
          category = category,
          title = cleanTitle,
          amountCents = amountCents,
          isSchoolDay = isSchoolDay,
          notes = notes,
          isManuallyEntered = true,
          createdAtEpochMillis = now
        )
      )
      transactionDao.insert(
        TransactionEntity(
          type = TransactionType.EXPENSE,
          title = cleanTitle,
          amountCents = amountCents,
          date = dateIso,
          expenseId = expenseId,
          category = category.label,
          notes = notes,
          isManuallyEntered = true,
          createdAtEpochMillis = now
        )
      )
    }
  }

  suspend fun deleteExpense(expenseId: Long) {
    db.withTransaction {
      expenseDao.deleteById(expenseId)
      val linkedTx = transactionDao.getAll().firstOrNull { it.expenseId == expenseId && !it.isVoided }
      if (linkedTx != null) {
        if (linkedTx.fundedFromReserveCents > 0L) {
          restoreReserves(ReserveType.EMERGENCY_SAVINGS, linkedTx.fundedFromReserveCents)
        }
        transactionDao.update(
          linkedTx.copy(isVoided = true, isCorrected = true, correctionReason = "Expense deleted")
        )
      }
    }
  }

  suspend fun createReserve(
    reserveType: ReserveType,
    amountCents: Long,
    dateIso: String,
    targetDebtId: Long? = null,
    notes: String = ""
  ) {
    require(amountCents > 0L) { "Reserve amount must be positive." }
    val date = ManilaTime.parseDate(dateIso) ?: ManilaTime.today()
    val weekStartIso = ManilaTime.formatIso(ManilaTime.weekStart(date))
    val now = ManilaTime.nowMillis()
    val title = if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE) {
      "Debt Repayment Reserve"
    } else {
      "Emergency Savings"
    }
    db.withTransaction {
      val reserveId = reserveDao.insert(
        DebtReserveEntity(
          weekStartDate = weekStartIso,
          targetDebtId = targetDebtId,
          reserveType = reserveType,
          amountCents = amountCents,
          appliedAmountCents = 0L,
          status = ReserveStatus.RESERVED,
          notes = notes,
          isManuallyEntered = true,
          createdAtEpochMillis = now
        )
      )
      transactionDao.insert(
        TransactionEntity(
          type = if (reserveType == ReserveType.DEBT_REPAYMENT_RESERVE) {
            TransactionType.DEBT_RESERVE
          } else {
            TransactionType.EMERGENCY_SAVINGS
          },
          title = title,
          amountCents = amountCents,
          date = ManilaTime.formatIso(date),
          debtId = targetDebtId,
          reserveId = reserveId,
          category = reserveType.label,
          notes = notes,
          isManuallyEntered = true,
          createdAtEpochMillis = now
        )
      )
    }
  }

  suspend fun releaseReserve(reserveId: Long) {
    db.withTransaction {
      val reserve = reserveDao.getById(reserveId) ?: return@withTransaction
      if (reserve.status != ReserveStatus.RESERVED) return@withTransaction
      val today = ManilaTime.today()
      val currentWeekStartIso = ManilaTime.formatIso(ManilaTime.weekStart(today))
      val remaining = reserve.remainingReservedCents

      if (reserve.weekStartDate == currentWeekStartIso && reserve.appliedAmountCents == 0L) {
        reserveDao.update(reserve.copy(status = ReserveStatus.RELEASED))
        val linkedTx = transactionDao.getAll().firstOrNull { it.reserveId == reserveId && !it.isVoided }
        if (linkedTx != null) {
          transactionDao.update(
            linkedTx.copy(isVoided = true, isCorrected = true, correctionReason = "Reserve released back to spending cash")
          )
        }
      } else if (remaining > 0L) {
        reserveDao.update(
          reserve.copy(
            appliedAmountCents = reserve.amountCents,
            status = ReserveStatus.APPLIED
          )
        )
        transactionDao.insert(
          TransactionEntity(
            type = TransactionType.ADJUSTMENT,
            title = "Released ${reserve.reserveType.label.lowercase()}",
            amountCents = remaining,
            date = ManilaTime.formatIso(today),
            debtId = reserve.targetDebtId,
            reserveId = reserve.id,
            fundedFromReserveCents = remaining,
            category = "Reserve Release",
            notes = "Moved back to this week's spending cash",
            isManuallyEntered = true,
            createdAtEpochMillis = ManilaTime.nowMillis()
          )
        )
      }
    }
  }

  suspend fun withdrawEmergencySavings(
    amountCents: Long,
    dateIso: String,
    logAsExpense: Boolean,
    title: String,
    notes: String = ""
  ): Boolean {
    if (amountCents <= 0L) return false
    return db.withTransaction {
      val candidates = reserveDao.getAll()
        .filter {
          it.reserveType == ReserveType.EMERGENCY_SAVINGS &&
            it.status == ReserveStatus.RESERVED &&
            it.remainingReservedCents > 0L
        }
        .sortedWith(compareBy<DebtReserveEntity> { it.createdAtEpochMillis }.thenBy { it.id })

      val totalAvailable = candidates.sumOf { it.remainingReservedCents }
      if (amountCents > totalAvailable) return@withTransaction false

      var remainingToWithdraw = amountCents
      var primaryReserveId: Long? = null
      for (res in candidates) {
        if (remainingToWithdraw <= 0L) break
        if (primaryReserveId == null) primaryReserveId = res.id
        val take = minOf(res.remainingReservedCents, remainingToWithdraw)
        val newApplied = res.appliedAmountCents + take
        val newStatus = if (newApplied >= res.amountCents) ReserveStatus.APPLIED else ReserveStatus.RESERVED
        reserveDao.update(res.copy(appliedAmountCents = newApplied, status = newStatus))
        remainingToWithdraw -= take
      }

      val now = ManilaTime.nowMillis()
      if (logAsExpense) {
        val cleanTitle = title.ifBlank { "Emergency expense" }
        val expenseId = expenseDao.insert(
          ExpenseEntity(
            date = dateIso,
            category = ExpenseCategory.EMERGENCY,
            title = cleanTitle,
            amountCents = amountCents,
            isSchoolDay = false,
            notes = notes,
            isManuallyEntered = true,
            createdAtEpochMillis = now
          )
        )
        transactionDao.insert(
          TransactionEntity(
            type = TransactionType.EXPENSE,
            title = cleanTitle,
            amountCents = amountCents,
            date = dateIso,
            reserveId = primaryReserveId,
            expenseId = expenseId,
            fundedFromReserveCents = amountCents,
            category = ExpenseCategory.EMERGENCY.label,
            notes = notes,
            isManuallyEntered = true,
            createdAtEpochMillis = now
          )
        )
      } else {
        transactionDao.insert(
          TransactionEntity(
            type = TransactionType.ADJUSTMENT,
            title = title.ifBlank { "Emergency savings withdrawal" },
            amountCents = amountCents,
            date = dateIso,
            reserveId = primaryReserveId,
            fundedFromReserveCents = amountCents,
            category = "Emergency Withdrawal",
            notes = notes,
            isManuallyEntered = true,
            createdAtEpochMillis = now
          )
        )
      }
      true
    }
  }

  suspend fun saveDebt(debt: DebtEntity): Long {
    val now = ManilaTime.nowMillis()
    val normalizedStatus = if (debt.remainingBalanceCents <= 0L && debt.status == DebtStatus.ACTIVE) {
      DebtStatus.PAID
    } else {
      debt.status
    }
    val normalized = debt.copy(
      status = normalizedStatus,
      updatedAtEpochMillis = now,
      createdAtEpochMillis = if (debt.id == 0L) now else debt.createdAtEpochMillis
    )
    return if (debt.id == 0L) {
      debtDao.insert(normalized)
    } else {
      debtDao.update(normalized)
      debt.id
    }
  }

  /**
   * Atomically saves a user-confirmed scanned debt together with its extracted/edited
   * schedule rows (including irregular due dates and prior-paid history) and any
   * explicitly opted-in private screenshot attachments.
   */
  suspend fun saveImportedDebtWithSchedule(
    debt: DebtEntity,
    scheduleRows: List<InstallmentEntity>,
    attachmentSources: List<RetainedAttachmentSource> = emptyList()
  ): Long {
    val now = ManilaTime.nowMillis()
    val firstUnpaidDated = scheduleRows
      .filter { it.status.isPayableInstallment }
      .firstOrNull { !it.dueDate.isNullOrBlank() }

    val resolvedNextDue = debt.nextDueDate ?: firstUnpaidDated?.dueDate
    val resolvedStatus = when {
      debt.status == DebtStatus.ARCHIVED -> DebtStatus.ARCHIVED
      debt.remainingBalanceCents <= 0L -> DebtStatus.PAID
      else -> DebtStatus.ACTIVE
    }
    val normalizedDebt = debt.copy(
      id = 0L,
      nextDueDate = resolvedNextDue,
      status = resolvedStatus,
      isManuallyEntered = true,
      createdAtEpochMillis = now,
      updatedAtEpochMillis = now
    )

    val copiedFiles = mutableListOf<Pair<File, RetainedAttachmentSource>>()
    return try {
      val insertedId = db.withTransaction {
        val debtId = debtDao.insert(normalizedDebt)
        if (scheduleRows.isNotEmpty()) {
          val normalizedRows = scheduleRows.mapIndexed { idx, row ->
            row.copy(
              id = 0L,
              debtId = debtId,
              installmentNumber = row.installmentNumber.takeIf { it > 0 } ?: (idx + 1),
              scheduleVersion = row.scheduleVersion.coerceAtLeast(normalizedDebt.scheduleVersion),
              linkedTransactionId = null,
              isManuallyEntered = true
            )
          }
          installmentDao.insertAll(normalizedRows)
        }
        val dir = attachmentStorageDir
        if (attachmentSources.isNotEmpty() && dir != null) {
          val entities = withContext(Dispatchers.IO) {
            if (!dir.exists()) dir.mkdirs()
            val validSources = attachmentSources.filter { it.sourceFile.exists() && it.sourceFile.isFile }
            validSources.mapIndexed { idx, src ->
              val isPdfSource = src.isPdf || src.sourceFile.extension.equals("pdf", ignoreCase = true)
              val normalizedRotation = if (isPdfSource) 0 else ((src.rotationDegrees % 360) + 360) % 360
              val origExt = if (isPdfSource) {
                "pdf"
              } else {
                src.sourceFile.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
              }
              val ext = if (!isPdfSource && normalizedRotation != 0) "jpg" else origExt
              val destName = "debt_${debtId}_s${src.screenshotIndex}_${now}_$idx.$ext"
              val destFile = File(dir, destName)
              val savedRotated = if (!isPdfSource && normalizedRotation != 0) {
                ImageScanPreprocessor.writeRotatedAttachmentJpeg(
                  sourceFile = src.sourceFile,
                  destFile = destFile,
                  extraRotationDegrees = normalizedRotation
                )
              } else {
                false
              }
              if (!savedRotated) {
                src.sourceFile.copyTo(destFile, overwrite = true)
              }
              copiedFiles.add(destFile to src)
              DebtAttachmentEntity(
                debtId = debtId,
                fileName = destName,
                displayLabel = src.displayLabel,
                screenshotIndex = src.screenshotIndex,
                createdAtEpochMillis = now
              )
            }
          }
          if (entities.isNotEmpty()) {
            attachmentDao.insertAll(entities)
          }
        }
        debtId
      }
      insertedId
    } catch (t: Throwable) {
      withContext(Dispatchers.IO) {
        copiedFiles.forEach { (f, _) -> runCatching { f.delete() } }
      }
      throw t
    }
  }

  /**
   * Updates an existing tracked debt with newly analyzed document/schedule evidence while
   * preserving earlier schedule versions (e.g., v1 retained when importing restructured v2),
   * existing BaonPlan payment links, and prior attachments.
   */
  suspend fun updateExistingDebtWithImportedSchedule(
    existingDebtId: Long,
    updatedDebt: DebtEntity,
    newScheduleRows: List<InstallmentEntity>,
    attachmentSources: List<RetainedAttachmentSource> = emptyList()
  ): Long {
    val existing = debtDao.getById(existingDebtId)
      ?: return saveImportedDebtWithSchedule(updatedDebt, newScheduleRows, attachmentSources)

    val now = ManilaTime.nowMillis()
    val targetVersion = updatedDebt.scheduleVersion.coerceAtLeast(existing.scheduleVersion)
    val firstUnpaidDated = newScheduleRows
      .filter { it.status.isPayableInstallment }
      .firstOrNull { !it.dueDate.isNullOrBlank() }

    val resolvedNextDue = updatedDebt.nextDueDate ?: firstUnpaidDated?.dueDate ?: existing.nextDueDate
    val resolvedStatus = when {
      updatedDebt.status == DebtStatus.ARCHIVED -> DebtStatus.ARCHIVED
      updatedDebt.remainingBalanceCents <= 0L -> DebtStatus.PAID
      else -> DebtStatus.ACTIVE
    }

    val mergedDebt = updatedDebt.copy(
      id = existing.id,
      trackedPaidCents = maxOf(existing.trackedPaidCents, updatedDebt.trackedPaidCents),
      nextDueDate = resolvedNextDue,
      status = resolvedStatus,
      scheduleVersion = targetVersion,
      isManuallyEntered = true,
      createdAtEpochMillis = existing.createdAtEpochMillis,
      updatedAtEpochMillis = now
    )

    val copiedFiles = mutableListOf<Pair<File, RetainedAttachmentSource>>()
    return try {
      db.withTransaction {
        debtDao.update(mergedDebt)

        if (newScheduleRows.isNotEmpty()) {
          val existingRows = installmentDao.getByDebtId(existingDebtId)
          if (targetVersion > existing.scheduleVersion) {
            // Retain prior schedule versions (< targetVersion) as historical records
            installmentDao.deleteByDebtIdFromVersion(existingDebtId, targetVersion)
            val normalizedRows = newScheduleRows.mapIndexed { idx, row ->
              row.copy(
                id = 0L,
                debtId = existingDebtId,
                installmentNumber = row.installmentNumber.takeIf { it > 0 } ?: (idx + 1),
                scheduleVersion = row.scheduleVersion.coerceAtLeast(targetVersion),
                linkedTransactionId = null,
                isManuallyEntered = true
              )
            }
            installmentDao.insertAll(normalizedRows)
          } else {
            // Same schedule version: preserve any BaonPlan-linked transaction rows while refreshing schedule details
            val preservedByNum = existingRows
              .filter { it.scheduleVersion == targetVersion && it.linkedTransactionId != null }
              .associateBy { it.installmentNumber }
            installmentDao.deleteByDebtIdFromVersion(existingDebtId, targetVersion)
            val normalizedRows = newScheduleRows.mapIndexed { idx, row ->
              val num = row.installmentNumber.takeIf { it > 0 } ?: (idx + 1)
              val preserved = preservedByNum[num]
              if (preserved != null) {
                row.copy(
                  id = 0L,
                  debtId = existingDebtId,
                  installmentNumber = num,
                  scheduleVersion = targetVersion,
                  status = preserved.status,
                  paidAmountCents = maxOf(row.paidAmountCents, preserved.paidAmountCents),
                  paidDate = preserved.paidDate ?: row.paidDate,
                  linkedTransactionId = preserved.linkedTransactionId,
                  isManuallyEntered = true
                )
              } else {
                row.copy(
                  id = 0L,
                  debtId = existingDebtId,
                  installmentNumber = num,
                  scheduleVersion = targetVersion,
                  linkedTransactionId = null,
                  isManuallyEntered = true
                )
              }
            }
            installmentDao.insertAll(normalizedRows)
          }
        }

        val dir = attachmentStorageDir
        if (attachmentSources.isNotEmpty() && dir != null) {
          val existingCount = attachmentDao.getByDebtId(existingDebtId).size
          val entities = withContext(Dispatchers.IO) {
            if (!dir.exists()) dir.mkdirs()
            val validSources = attachmentSources.filter { it.sourceFile.exists() && it.sourceFile.isFile }
            validSources.mapIndexed { idx, src ->
              val isPdfSource = src.isPdf || src.sourceFile.extension.equals("pdf", ignoreCase = true)
              val normalizedRotation = if (isPdfSource) 0 else ((src.rotationDegrees % 360) + 360) % 360
              val origExt = if (isPdfSource) {
                "pdf"
              } else {
                src.sourceFile.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
              }
              val ext = if (!isPdfSource && normalizedRotation != 0) "jpg" else origExt
              val destName = "debt_${existingDebtId}_s${src.screenshotIndex}_${now}_${existingCount + idx}.$ext"
              val destFile = File(dir, destName)
              val savedRotated = if (!isPdfSource && normalizedRotation != 0) {
                ImageScanPreprocessor.writeRotatedAttachmentJpeg(
                  sourceFile = src.sourceFile,
                  destFile = destFile,
                  extraRotationDegrees = normalizedRotation
                )
              } else {
                false
              }
              if (!savedRotated) {
                src.sourceFile.copyTo(destFile, overwrite = true)
              }
              copiedFiles.add(destFile to src)
              DebtAttachmentEntity(
                debtId = existingDebtId,
                fileName = destName,
                displayLabel = src.displayLabel,
                screenshotIndex = src.screenshotIndex,
                createdAtEpochMillis = now
              )
            }
          }
          if (entities.isNotEmpty()) {
            attachmentDao.insertAll(entities)
          }
        }
      }
      existingDebtId
    } catch (t: Throwable) {
      withContext(Dispatchers.IO) {
        copiedFiles.forEach { (f, _) -> runCatching { f.delete() } }
      }
      throw t
    }
  }

  suspend fun deleteDebt(debtId: Long) {
    var attachmentNames: List<String> = emptyList()
    db.withTransaction {
      attachmentNames = attachmentDao.getByDebtId(debtId).map { it.fileName }
      // Unlink any reserves pointing to this debt
      reserveDao.getAll()
        .filter { it.targetDebtId == debtId }
        .forEach { res ->
          reserveDao.update(res.copy(targetDebtId = null))
        }

      // Revert any reserve portions used by linked non-voided repayments and unlink/void transactions
      val linkedTxs = transactionDao.getAll().filter { it.debtId == debtId }
      for (tx in linkedTxs) {
        if (!tx.isVoided && tx.type == TransactionType.DEBT_REPAYMENT && tx.fundedFromReserveCents > 0L) {
          restoreReserves(ReserveType.DEBT_REPAYMENT_RESERVE, tx.fundedFromReserveCents)
        }
        transactionDao.update(
          tx.copy(
            debtId = null,
            installmentId = null,
            isVoided = true,
            isCorrected = true,
            correctionReason = tx.correctionReason ?: "Loan deleted"
          )
        )
      }

      debtDao.deleteById(debtId)
    }
    deleteStoredAttachmentsByName(attachmentNames)
  }

  private suspend fun deleteStoredAttachmentsByName(fileNames: List<String>) {
    if (fileNames.isEmpty() || attachmentStorageDir == null) return
    withContext(Dispatchers.IO) {
      fileNames.forEach { name ->
        resolveAttachmentFile(name)?.let { file ->
          runCatching { if (file.exists()) file.delete() }
        }
      }
    }
  }

  private suspend fun deleteAllStoredAttachments() {
    val dir = attachmentStorageDir ?: return
    withContext(Dispatchers.IO) {
      runCatching {
        if (dir.exists() && dir.isDirectory) {
          dir.listFiles()?.forEach { it.delete() }
        }
      }
    }
  }

  private suspend fun restoreReserves(type: ReserveType, amountToRestoreCents: Long) {
    if (amountToRestoreCents <= 0L) return
    var toRestore = amountToRestoreCents
    val reserves = reserveDao.getAll()
      .filter { it.reserveType == type && it.appliedAmountCents > 0L }
      .sortedByDescending { it.createdAtEpochMillis }
    for (res in reserves) {
      if (toRestore <= 0L) break
      val giveBack = minOf(res.appliedAmountCents, toRestore)
      reserveDao.update(
        res.copy(
          appliedAmountCents = res.appliedAmountCents - giveBack,
          status = ReserveStatus.RESERVED
        )
      )
      toRestore -= giveBack
    }
  }

  suspend fun setDebtArchived(debtId: Long, archived: Boolean) {
    db.withTransaction {
      val debt = debtDao.getById(debtId) ?: return@withTransaction
      val nextStatus = when {
        archived -> DebtStatus.ARCHIVED
        debt.remainingBalanceCents <= 0L -> DebtStatus.PAID
        else -> DebtStatus.ACTIVE
      }
      debtDao.update(debt.copy(status = nextStatus, updatedAtEpochMillis = ManilaTime.nowMillis()))
    }
  }

  suspend fun confirmDebtDate(
    debtId: Long,
    confirmedDateIso: String,
    generateFullSchedule: Boolean
  ) {
    val confirmedDate = ManilaTime.parseDate(confirmedDateIso) ?: return
    db.withTransaction {
      val debt = debtDao.getById(debtId) ?: return@withTransaction
      val updatedDebt = debt.copy(
        nextDueDate = ManilaTime.formatIso(confirmedDate),
        dateStatus = DueDateStatus.CONFIRMED,
        updatedAtEpochMillis = ManilaTime.nowMillis()
      )
      debtDao.update(updatedDebt)

      val existing = FinancialCalculator.effectiveInstallmentsForDebt(
        updatedDebt,
        installmentDao.getByDebtId(debtId)
      )
      val unpaid = existing.filter { it.status.isPayableInstallment }
      if (generateFullSchedule &&
        updatedDebt.frequency != RepaymentFrequency.UNKNOWN &&
        updatedDebt.frequency != RepaymentFrequency.IRREGULAR &&
        updatedDebt.frequency != RepaymentFrequency.TWICE_MONTHLY
      ) {
        val count = updatedDebt.remainingInstallmentsCount.takeIf { it > 0 }
          ?: if (updatedDebt.installmentAmountCents > 0L) {
            MoneyFormatter.divideCentsCeil(updatedDebt.remainingBalanceCents, updatedDebt.installmentAmountCents).toInt().coerceAtLeast(1)
          } else 1
        val specs = FinancialCalculator.generateInstallmentSchedule(
          startDate = confirmedDate,
          count = count,
          installmentAmountCents = updatedDebt.installmentAmountCents.takeIf { it > 0L } ?: updatedDebt.remainingBalanceCents,
          totalRemainingCents = updatedDebt.remainingBalanceCents,
          frequency = updatedDebt.frequency,
          customIntervalDays = updatedDebt.customIntervalDays,
          dateStatus = DueDateStatus.CONFIRMED
        )
        installmentDao.deleteUnpaidByDebtIdAndVersion(debtId, updatedDebt.scheduleVersion)
        val paidCount = existing.count { it.status == InstallmentStatus.PAID }
        installmentDao.insertAll(
          specs.mapIndexed { idx, spec ->
            InstallmentEntity(
              debtId = debtId,
              installmentNumber = paidCount + idx + 1,
              dueDate = spec.dueDateIso,
              amountDueCents = spec.amountDueCents,
              dateStatus = spec.dateStatus,
              status = InstallmentStatus.UPCOMING,
              isManuallyEntered = true,
              notes = "Confirmed schedule",
              scheduleVersion = updatedDebt.scheduleVersion
            )
          }
        )
      } else if (unpaid.isNotEmpty()) {
        val first = unpaid.first()
        installmentDao.update(
          first.copy(
            dueDate = ManilaTime.formatIso(confirmedDate),
            dateStatus = DueDateStatus.CONFIRMED
          )
        )
      } else {
        val amt = updatedDebt.installmentAmountCents.takeIf { it > 0L } ?: updatedDebt.remainingBalanceCents
        if (amt > 0L) {
          installmentDao.insert(
            InstallmentEntity(
              debtId = debtId,
              installmentNumber = existing.size + 1,
              dueDate = ManilaTime.formatIso(confirmedDate),
              amountDueCents = amt,
              dateStatus = DueDateStatus.CONFIRMED,
              status = InstallmentStatus.UPCOMING,
              isManuallyEntered = true,
              notes = "User confirmed due date",
              scheduleVersion = updatedDebt.scheduleVersion
            )
          )
        }
      }
    }
  }

  suspend fun generateInstallmentSchedule(
    debtId: Long,
    startDateIso: String,
    count: Int,
    installmentAmountCents: Long,
    frequency: RepaymentFrequency,
    customIntervalDays: Int?,
    dateStatus: DueDateStatus
  ): Boolean {
    val startDate = ManilaTime.parseDate(startDateIso) ?: return false
    if (frequency == RepaymentFrequency.UNKNOWN ||
      frequency == RepaymentFrequency.IRREGULAR ||
      frequency == RepaymentFrequency.TWICE_MONTHLY
    ) return false
    return db.withTransaction {
      val debt = debtDao.getById(debtId) ?: return@withTransaction false
      val specs = FinancialCalculator.generateInstallmentSchedule(
        startDate = startDate,
        count = count,
        installmentAmountCents = installmentAmountCents,
        totalRemainingCents = debt.remainingBalanceCents,
        frequency = frequency,
        customIntervalDays = customIntervalDays,
        dateStatus = dateStatus
      )
      if (specs.isEmpty()) return@withTransaction false

      val paidCount = FinancialCalculator.effectiveInstallmentsForDebt(
        debt,
        installmentDao.getByDebtId(debtId)
      ).count { it.status == InstallmentStatus.PAID }
      installmentDao.deleteUnpaidByDebtIdAndVersion(debtId, debt.scheduleVersion)
      installmentDao.insertAll(
        specs.mapIndexed { idx, spec ->
          InstallmentEntity(
            debtId = debtId,
            installmentNumber = paidCount + idx + 1,
            dueDate = spec.dueDateIso,
            amountDueCents = spec.amountDueCents,
            dateStatus = spec.dateStatus,
            status = InstallmentStatus.UPCOMING,
            isManuallyEntered = true,
            notes = "Generated schedule (${frequency.label})",
            scheduleVersion = debt.scheduleVersion
          )
        }
      )
      debtDao.update(
        debt.copy(
          frequency = frequency,
          customIntervalDays = customIntervalDays,
          installmentAmountCents = installmentAmountCents,
          remainingInstallmentsCount = specs.size,
          nextDueDate = specs.first().dueDateIso,
          dateStatus = dateStatus,
          updatedAtEpochMillis = ManilaTime.nowMillis()
        )
      )
      true
    }
  }

  /**
   * Records a full or partial repayment atomically without double-counting.
   * Returns created transaction ID, or null if debt has 0 remaining balance.
   */
  suspend fun recordRepayment(
    debtId: Long,
    amountCents: Long,
    dateIso: String,
    targetInstallmentId: Long? = null,
    useDebtReserve: Boolean = false,
    notes: String = ""
  ): Long? {
    require(amountCents > 0L) { "Repayment amount must be positive." }
    return db.withTransaction {
      recordRepaymentInternal(
        debtId = debtId,
        amountCents = amountCents,
        dateIso = dateIso,
        targetInstallmentId = targetInstallmentId,
        useDebtReserve = useDebtReserve,
        notes = notes
      )
    }
  }

  /**
   * Marks an installment paid atomically. Idempotent: refuses to double-pay an already PAID installment.
   */
  suspend fun markInstallmentPaid(
    installmentId: Long,
    dateIso: String,
    useDebtReserve: Boolean = false
  ): Long? {
    return db.withTransaction {
      val inst = installmentDao.getById(installmentId) ?: return@withTransaction null
      if (!inst.status.isPayableInstallment) return@withTransaction null
      val remainingDue = (inst.amountDueCents - inst.paidAmountCents).coerceAtLeast(0L)
      if (remainingDue <= 0L) return@withTransaction null
      recordRepaymentInternal(
        debtId = inst.debtId,
        amountCents = remainingDue,
        dateIso = dateIso,
        targetInstallmentId = inst.id,
        useDebtReserve = useDebtReserve,
        notes = "Installment #${inst.installmentNumber} paid"
      )
    }
  }

  private suspend fun recordRepaymentInternal(
    debtId: Long,
    amountCents: Long,
    dateIso: String,
    targetInstallmentId: Long?,
    useDebtReserve: Boolean,
    notes: String
  ): Long? {
    val debt = debtDao.getById(debtId) ?: return null
    if (debt.remainingBalanceCents <= 0L) return null

    val appliedPayment = amountCents.coerceAtMost(debt.remainingBalanceCents)
    if (appliedPayment <= 0L) return null

    // Optionally consume from active DEBT_REPAYMENT_RESERVE without double-subtracting cash
    var fundedFromReserve = 0L
    if (useDebtReserve) {
      val candidateReserves = reserveDao.getAll()
        .filter {
          it.reserveType == ReserveType.DEBT_REPAYMENT_RESERVE &&
            it.status == ReserveStatus.RESERVED &&
            it.remainingReservedCents > 0L
        }
        .sortedBy { if (it.targetDebtId == debtId) 0 else 1 }

      var remainingToFund = appliedPayment
      for (res in candidateReserves) {
        if (remainingToFund <= 0L) break
        val take = minOf(res.remainingReservedCents, remainingToFund)
        val newApplied = res.appliedAmountCents + take
        val newStatus = if (newApplied >= res.amountCents) ReserveStatus.APPLIED else ReserveStatus.RESERVED
        reserveDao.update(res.copy(appliedAmountCents = newApplied, status = newStatus))
        fundedFromReserve += take
        remainingToFund -= take
      }
    }

    val now = ManilaTime.nowMillis()
    val txId = transactionDao.insert(
      TransactionEntity(
        type = TransactionType.DEBT_REPAYMENT,
        title = "Repayment: ${debt.name}",
        amountCents = appliedPayment,
        date = dateIso,
        debtId = debtId,
        installmentId = targetInstallmentId,
        fundedFromReserveCents = fundedFromReserve,
        category = "Debt Repayment",
        notes = notes,
        isManuallyEntered = true,
        createdAtEpochMillis = now
      )
    )

    // Apply payment cleanly to pending installments of active scheduleVersion
    val allInst = FinancialCalculator.effectiveInstallmentsForDebt(debt, installmentDao.getByDebtId(debtId))
    val pendingInst = allInst.filter { it.status.isPayableInstallment }
    val orderedPending = if (targetInstallmentId != null) {
      val target = pendingInst.firstOrNull { it.id == targetInstallmentId }
      if (target != null) listOf(target) + pendingInst.filter { it.id != targetInstallmentId } else pendingInst
    } else {
      pendingInst
    }

    var toAllocate = appliedPayment
    var completedInstCount = 0
    for (inst in orderedPending) {
      if (toAllocate <= 0L) break
      val remainingInstDue = (inst.amountDueCents - inst.paidAmountCents).coerceAtLeast(0L)
      if (remainingInstDue <= 0L) continue
      val chunk = minOf(toAllocate, remainingInstDue)
      val newPaid = inst.paidAmountCents + chunk
      val isNowPaid = newPaid >= inst.amountDueCents
      if (isNowPaid) completedInstCount++
      installmentDao.update(
        inst.copy(
          paidAmountCents = newPaid,
          status = if (isNowPaid) InstallmentStatus.PAID else InstallmentStatus.PARTIAL,
          paidDate = if (isNowPaid) dateIso else inst.paidDate,
          linkedTransactionId = txId
        )
      )
      toAllocate -= chunk
    }

    val newRemainingBalance = (debt.remainingBalanceCents - appliedPayment).coerceAtLeast(0L)
    val newTrackedPaid = debt.trackedPaidCents + appliedPayment
    val newStatus = if (newRemainingBalance == 0L) DebtStatus.PAID else debt.status

    val updatedInstallments = FinancialCalculator.effectiveInstallmentsForDebt(
      debt,
      installmentDao.getByDebtId(debtId)
    )
    val nextUnpaid = updatedInstallments.firstOrNull { it.status.isPayableInstallment }

    val nextRemainingInstallments = when {
      newRemainingBalance == 0L -> 0
      updatedInstallments.any { it.status.isPayableInstallment } &&
        updatedInstallments.count { it.status.isPayableInstallment } >= debt.remainingInstallmentsCount ->
        updatedInstallments.count { it.status.isPayableInstallment }
      debt.remainingInstallmentsCount > 0 -> {
        if (completedInstCount > 0) {
          (debt.remainingInstallmentsCount - completedInstCount).coerceAtLeast(1)
        } else if (debt.installmentAmountCents > 0L) {
          MoneyFormatter.divideCentsCeil(newRemainingBalance, debt.installmentAmountCents).toInt().coerceAtLeast(1)
        } else {
          debt.remainingInstallmentsCount
        }
      }
      else -> 0
    }

    // Advance nextDueDate ONLY if next unpaid installment exists OR frequency is known and prior nextDueDate existed
    val newNextDueDate: String?
    val newDateStatus: DueDateStatus
    if (newRemainingBalance == 0L) {
      newNextDueDate = null
      newDateStatus = DueDateStatus.CONFIRMED
    } else if (nextUnpaid != null) {
      newNextDueDate = nextUnpaid.dueDate
      newDateStatus = nextUnpaid.dateStatus
    } else if (debt.nextDueDate != null &&
      debt.frequency != RepaymentFrequency.UNKNOWN &&
      debt.frequency != RepaymentFrequency.IRREGULAR &&
      debt.frequency != RepaymentFrequency.TWICE_MONTHLY
    ) {
      val parsedCurrent = ManilaTime.parseDate(debt.nextDueDate)
      val advanced = if (parsedCurrent != null && (completedInstCount > 0 || appliedPayment >= debt.installmentAmountCents)) {
        ManilaTime.advanceDate(parsedCurrent, debt.frequency, debt.customIntervalDays)
      } else {
        parsedCurrent
      }
      newNextDueDate = advanced?.let { ManilaTime.formatIso(it) }
      newDateStatus = debt.dateStatus
    } else {
      newNextDueDate = null
      newDateStatus = debt.dateStatus
    }

    debtDao.update(
      debt.copy(
        trackedPaidCents = newTrackedPaid,
        remainingBalanceCents = newRemainingBalance,
        remainingInstallmentsCount = nextRemainingInstallments,
        lastKnownPaidDate = dateIso,
        nextDueDate = newNextDueDate,
        dateStatus = newDateStatus,
        status = newStatus,
        updatedAtEpochMillis = now
      )
    )

    return txId
  }

  /**
   * Corrects or voids a mistaken transaction atomically without double-counting or corrupting balances.
   */
  suspend fun correctOrVoidTransaction(
    transactionId: Long,
    newAmountCents: Long? = null,
    newTitle: String? = null,
    newDateIso: String? = null,
    newNotes: String? = null,
    voidTransaction: Boolean = false,
    reason: String = "User correction"
  ) {
    db.withTransaction {
      val tx = transactionDao.getById(transactionId) ?: return@withTransaction
      if (tx.isVoided) return@withTransaction

      val effectiveTitle = newTitle?.trim()?.takeIf { it.isNotBlank() } ?: tx.title
      val effectiveDate = newDateIso?.takeIf { it.isNotBlank() && ManilaTime.parseDate(it) != null } ?: tx.date
      val effectiveNotes = newNotes ?: tx.notes

      when (tx.type) {
        TransactionType.DEBT_REPAYMENT -> {
          val debt = tx.debtId?.let { debtDao.getById(it) }
          if (voidTransaction) {
            if (debt != null) {
              // Revert installments linked to this transaction first so unpaid count & dates stay accurate
              val allInstRaw = installmentDao.getByDebtId(debt.id)
              val effectiveInstBeforeRevert = FinancialCalculator.effectiveInstallmentsForDebt(debt, allInstRaw)
              val primaryLinked = allInstRaw
                .filter { it.linkedTransactionId == tx.id || it.id == tx.installmentId }
                .sortedByDescending { it.installmentNumber }
              val fallbackLinked = effectiveInstBeforeRevert
                .filter { it.paidAmountCents > 0L && it.linkedTransactionId != tx.id && it.id != tx.installmentId }
                .sortedByDescending { it.installmentNumber }

              var toRevert = tx.amountCents
              for (inst in primaryLinked + fallbackLinked) {
                if (toRevert <= 0L) break
                if (inst.paidAmountCents <= 0L) continue
                val revertAmt = minOf(inst.paidAmountCents, toRevert)
                val newPaid = (inst.paidAmountCents - revertAmt).coerceAtLeast(0L)
                val nextStatus = when {
                  newPaid >= inst.amountDueCents -> InstallmentStatus.PAID
                  newPaid > 0L -> InstallmentStatus.PARTIAL
                  else -> InstallmentStatus.UPCOMING
                }
                installmentDao.update(
                  inst.copy(
                    paidAmountCents = newPaid,
                    status = nextStatus,
                    paidDate = if (nextStatus == InstallmentStatus.PAID) inst.paidDate else null,
                    linkedTransactionId = if (newPaid == 0L || inst.linkedTransactionId == tx.id) null else inst.linkedTransactionId
                  )
                )
                toRevert -= revertAmt
              }

              val allInstAfterRevert = FinancialCalculator.effectiveInstallmentsForDebt(
                debt,
                installmentDao.getByDebtId(debt.id)
              )
              val firstUnpaidAfterRevert = allInstAfterRevert.firstOrNull { it.status.isPayableInstallment }
              val unpaidCountAfterRevert = allInstAfterRevert.count { it.status.isPayableInstallment }

              val restoredRemaining = debt.remainingBalanceCents + tx.amountCents
              val restoredTracked = (debt.trackedPaidCents - tx.amountCents).coerceAtLeast(0L)
              val restoredStatus = if (restoredRemaining > 0L && debt.status == DebtStatus.PAID) {
                DebtStatus.ACTIVE
              } else {
                debt.status
              }
              val restoredInstallmentCount = when {
                unpaidCountAfterRevert > 0 -> unpaidCountAfterRevert
                debt.installmentAmountCents > 0L && restoredRemaining > 0L ->
                  MoneyFormatter.divideCentsCeil(restoredRemaining, debt.installmentAmountCents).toInt().coerceAtLeast(1)
                else -> debt.remainingInstallmentsCount
              }
              val restoredNextDueDate = firstUnpaidAfterRevert?.dueDate ?: debt.nextDueDate
              val restoredDateStatus = when {
                firstUnpaidAfterRevert != null -> firstUnpaidAfterRevert.dateStatus
                restoredNextDueDate == null && restoredRemaining > 0L && debt.dateStatus == DueDateStatus.CONFIRMED ->
                  DueDateStatus.UNCONFIRMED
                else -> debt.dateStatus
              }

              val remainingRepaymentDates = transactionDao.getAll()
                .filter { !it.isVoided && it.id != tx.id && it.debtId == debt.id && it.type == TransactionType.DEBT_REPAYMENT }
                .map { it.date }
                .maxOrNull()

              debtDao.update(
                debt.copy(
                  trackedPaidCents = restoredTracked,
                  remainingBalanceCents = restoredRemaining,
                  remainingInstallmentsCount = restoredInstallmentCount,
                  nextDueDate = restoredNextDueDate,
                  dateStatus = restoredDateStatus,
                  lastKnownPaidDate = remainingRepaymentDates,
                  status = restoredStatus,
                  updatedAtEpochMillis = ManilaTime.nowMillis()
                )
              )
            }
            // Restore funded reserve portion if applicable
            if (tx.fundedFromReserveCents > 0L) {
              restoreReserves(ReserveType.DEBT_REPAYMENT_RESERVE, tx.fundedFromReserveCents)
            }
            transactionDao.update(
              tx.copy(
                title = effectiveTitle,
                date = effectiveDate,
                notes = effectiveNotes,
                isVoided = true,
                isCorrected = true,
                correctionReason = reason
              )
            )
          } else {
            val targetAmount = newAmountCents ?: tx.amountCents
            if (targetAmount <= 0L) {
              throw IllegalArgumentException("Repayment amount must be positive.")
            }
            if (debt != null) {
              val maxAllowed = tx.amountCents + debt.remainingBalanceCents
              if (targetAmount > maxAllowed) {
                throw IllegalArgumentException(
                  "Cannot exceed remaining loan balance (${MoneyFormatter.formatPeso(maxAllowed)})."
                )
              }
            }

            // If edited downward below the reserve-funded portion, restore unused reserve back
            var updatedFundedReserve = tx.fundedFromReserveCents
            if (tx.fundedFromReserveCents > 0L && targetAmount < tx.fundedFromReserveCents) {
              val excessReserve = tx.fundedFromReserveCents - targetAmount
              restoreReserves(ReserveType.DEBT_REPAYMENT_RESERVE, excessReserve)
              updatedFundedReserve = targetAmount
            }

            val delta = targetAmount - tx.amountCents
            if (debt != null && (delta != 0L || effectiveDate != tx.date)) {
              val allInstRaw = installmentDao.getByDebtId(debt.id)
              val allInstBefore = FinancialCalculator.effectiveInstallmentsForDebt(debt, allInstRaw)
              if (delta < 0L) {
                var toRevert = -delta
                val primary = allInstRaw
                  .filter { it.linkedTransactionId == tx.id || it.id == tx.installmentId }
                  .sortedByDescending { it.installmentNumber }
                val fallback = allInstBefore
                  .filter { it.paidAmountCents > 0L && it.linkedTransactionId != tx.id && it.id != tx.installmentId }
                  .sortedByDescending { it.installmentNumber }
                for (inst in primary + fallback) {
                  if (toRevert <= 0L) break
                  if (inst.paidAmountCents <= 0L) continue
                  val revertAmt = minOf(inst.paidAmountCents, toRevert)
                  val newPaid = (inst.paidAmountCents - revertAmt).coerceAtLeast(0L)
                  val nextStatus = when {
                    newPaid >= inst.amountDueCents -> InstallmentStatus.PAID
                    newPaid > 0L -> InstallmentStatus.PARTIAL
                    else -> InstallmentStatus.UPCOMING
                  }
                  installmentDao.update(
                    inst.copy(
                      paidAmountCents = newPaid,
                      status = nextStatus,
                      paidDate = if (nextStatus == InstallmentStatus.PAID) inst.paidDate else null,
                      linkedTransactionId = if (newPaid == 0L) null else inst.linkedTransactionId
                    )
                  )
                  toRevert -= revertAmt
                }
              } else if (delta > 0L) {
                val pending = allInstBefore.filter { it.status.isPayableInstallment }
                val ordered = if (tx.installmentId != null) {
                  val target = pending.firstOrNull { it.id == tx.installmentId }
                  if (target != null) listOf(target) + pending.filter { it.id != tx.installmentId } else pending
                } else {
                  pending
                }
                var toAllocate = delta
                for (inst in ordered) {
                  if (toAllocate <= 0L) break
                  val remainingDue = (inst.amountDueCents - inst.paidAmountCents).coerceAtLeast(0L)
                  if (remainingDue <= 0L) continue
                  val chunk = minOf(toAllocate, remainingDue)
                  val newPaid = inst.paidAmountCents + chunk
                  val isNowPaid = newPaid >= inst.amountDueCents
                  installmentDao.update(
                    inst.copy(
                      paidAmountCents = newPaid,
                      status = if (isNowPaid) InstallmentStatus.PAID else InstallmentStatus.PARTIAL,
                      paidDate = if (isNowPaid) effectiveDate else inst.paidDate,
                      linkedTransactionId = tx.id
                    )
                  )
                  toAllocate -= chunk
                }
              }

              if (effectiveDate != tx.date) {
                val refreshedForDates = installmentDao.getByDebtId(debt.id)
                for (inst in refreshedForDates.filter { it.linkedTransactionId == tx.id && it.status == InstallmentStatus.PAID }) {
                  if (inst.paidDate != effectiveDate) {
                    installmentDao.update(inst.copy(paidDate = effectiveDate))
                  }
                }
              }

              val allInstAfter = FinancialCalculator.effectiveInstallmentsForDebt(
                debt,
                installmentDao.getByDebtId(debt.id)
              )
              val firstUnpaid = allInstAfter.firstOrNull { it.status.isPayableInstallment }
              val unpaidCount = allInstAfter.count { it.status.isPayableInstallment }

              val newTracked = (debt.trackedPaidCents + delta).coerceAtLeast(0L)
              val newRemaining = (debt.remainingBalanceCents - delta).coerceAtLeast(0L)
              val newStatus = when {
                newRemaining == 0L -> DebtStatus.PAID
                debt.status == DebtStatus.PAID && newRemaining > 0L -> DebtStatus.ACTIVE
                else -> debt.status
              }
              val newCount = when {
                newRemaining == 0L -> 0
                allInstAfter.isNotEmpty() -> unpaidCount
                debt.installmentAmountCents > 0L ->
                  MoneyFormatter.divideCentsCeil(newRemaining, debt.installmentAmountCents).toInt().coerceAtLeast(1)
                else -> debt.remainingInstallmentsCount
              }
              val newNextDueDate: String?
              val newDateStatus: DueDateStatus
              if (newRemaining == 0L) {
                newNextDueDate = null
                newDateStatus = DueDateStatus.CONFIRMED
              } else if (firstUnpaid != null) {
                newNextDueDate = firstUnpaid.dueDate
                newDateStatus = firstUnpaid.dateStatus
              } else {
                newNextDueDate = debt.nextDueDate
                newDateStatus = if (debt.nextDueDate == null && debt.dateStatus == DueDateStatus.CONFIRMED) {
                  DueDateStatus.UNCONFIRMED
                } else {
                  debt.dateStatus
                }
              }
              val latestPaidDate = transactionDao.getAll()
                .filter { !it.isVoided && it.debtId == debt.id && it.type == TransactionType.DEBT_REPAYMENT }
                .map { if (it.id == tx.id) effectiveDate else it.date }
                .maxOrNull()

              debtDao.update(
                debt.copy(
                  trackedPaidCents = newTracked,
                  remainingBalanceCents = newRemaining,
                  remainingInstallmentsCount = newCount,
                  nextDueDate = newNextDueDate,
                  dateStatus = newDateStatus,
                  lastKnownPaidDate = latestPaidDate,
                  status = newStatus,
                  updatedAtEpochMillis = ManilaTime.nowMillis()
                )
              )
            }
            transactionDao.update(
              tx.copy(
                title = effectiveTitle,
                amountCents = targetAmount,
                date = effectiveDate,
                fundedFromReserveCents = updatedFundedReserve,
                notes = effectiveNotes,
                isCorrected = true,
                correctionReason = reason
              )
            )
          }
        }

        TransactionType.ALLOWANCE -> {
          val record = tx.allowanceId?.let { allowanceDao.getById(it) }
          val linkedOcc = record?.incomeOccurrenceId?.let { incomeOccurrenceDao.getById(it) }
            ?: record?.let { incomeOccurrenceDao.findByLinkedAllowanceId(it.id) }
          if (voidTransaction) {
            if (record != null) allowanceDao.deleteById(record.id)
            if (linkedOcc != null) {
              incomeOccurrenceDao.update(
                linkedOcc.copy(
                  status = IncomeOccurrenceStatus.EXPECTED,
                  actualReceivedAmountCents = 0L,
                  actualReceivedDateIso = null,
                  linkedAllowanceRecordId = null,
                  updatedAtEpochMillis = ManilaTime.nowMillis()
                )
              )
            }
            transactionDao.update(
              tx.copy(isVoided = true, isCorrected = true, correctionReason = reason)
            )
          } else {
            val targetAmount = newAmountCents ?: tx.amountCents
            if (targetAmount <= 0L) {
              throw IllegalArgumentException("Allowance amount must be positive.")
            }
            if (record != null) {
              val parsed = ManilaTime.parseDate(effectiveDate) ?: LocalDate.now(ManilaTime.ZONE)
              allowanceDao.update(
                record.copy(
                  amountCents = targetAmount,
                  receivedDate = effectiveDate,
                  weekStartDate = ManilaTime.formatIso(ManilaTime.weekStart(parsed)),
                  sourceLabel = effectiveTitle,
                  notes = effectiveNotes
                )
              )
            }
            if (linkedOcc != null) {
              val nextOccStatus = if (targetAmount < linkedOcc.expectedAmountCents) {
                IncomeOccurrenceStatus.PARTIALLY_RECEIVED
              } else {
                IncomeOccurrenceStatus.RECEIVED
              }
              incomeOccurrenceDao.update(
                linkedOcc.copy(
                  actualReceivedAmountCents = targetAmount,
                  actualReceivedDateIso = effectiveDate,
                  status = nextOccStatus,
                  notes = effectiveNotes,
                  updatedAtEpochMillis = ManilaTime.nowMillis()
                )
              )
            }
            transactionDao.update(
              tx.copy(
                title = effectiveTitle,
                amountCents = targetAmount,
                date = effectiveDate,
                notes = effectiveNotes,
                isCorrected = true,
                correctionReason = reason
              )
            )
          }
        }

        TransactionType.EXPENSE -> {
          val expense = tx.expenseId?.let { expenseDao.getById(it) }
          if (voidTransaction) {
            if (expense != null) expenseDao.deleteById(expense.id)
            if (tx.fundedFromReserveCents > 0L) {
              restoreReserves(ReserveType.EMERGENCY_SAVINGS, tx.fundedFromReserveCents)
            }
            transactionDao.update(
              tx.copy(isVoided = true, isCorrected = true, correctionReason = reason)
            )
          } else {
            val targetAmount = newAmountCents ?: tx.amountCents
            if (targetAmount <= 0L) {
              throw IllegalArgumentException("Expense amount must be positive.")
            }
            var updatedFundedReserve = tx.fundedFromReserveCents
            if (tx.fundedFromReserveCents > 0L && targetAmount < tx.fundedFromReserveCents) {
              val excessReserve = tx.fundedFromReserveCents - targetAmount
              restoreReserves(ReserveType.EMERGENCY_SAVINGS, excessReserve)
              updatedFundedReserve = targetAmount
            }
            if (expense != null) {
              expenseDao.update(
                expense.copy(
                  amountCents = targetAmount,
                  date = effectiveDate,
                  title = effectiveTitle,
                  notes = effectiveNotes
                )
              )
            }
            transactionDao.update(
              tx.copy(
                title = effectiveTitle,
                amountCents = targetAmount,
                date = effectiveDate,
                fundedFromReserveCents = updatedFundedReserve,
                notes = effectiveNotes,
                isCorrected = true,
                correctionReason = reason
              )
            )
          }
        }

        TransactionType.DEBT_RESERVE, TransactionType.EMERGENCY_SAVINGS -> {
          val reserve = tx.reserveId?.let { reserveDao.getById(it) }
          if (voidTransaction) {
            if (reserve != null && reserve.appliedAmountCents > 0L) {
              throw IllegalStateException(
                "Cannot void a reserve that already funded payments or withdrawals (${MoneyFormatter.formatPeso(reserve.appliedAmountCents)} used). Void those transactions first, or release only the unused remainder from Budget."
              )
            }
            if (reserve != null) {
              reserveDao.update(reserve.copy(status = ReserveStatus.RELEASED))
            }
            transactionDao.update(
              tx.copy(isVoided = true, isCorrected = true, correctionReason = reason)
            )
          } else {
            val targetAmount = newAmountCents ?: tx.amountCents
            if (targetAmount <= 0L) {
              throw IllegalArgumentException("Reserve amount must be positive.")
            }
            if (reserve != null) {
              if (targetAmount < reserve.appliedAmountCents) {
                throw IllegalArgumentException(
                  "Reserve amount cannot be less than ${MoneyFormatter.formatPeso(reserve.appliedAmountCents)} already used."
                )
              }
              val parsed = ManilaTime.parseDate(effectiveDate) ?: LocalDate.now(ManilaTime.ZONE)
              reserveDao.update(
                reserve.copy(
                  amountCents = targetAmount,
                  weekStartDate = ManilaTime.formatIso(ManilaTime.weekStart(parsed)),
                  notes = effectiveNotes,
                  status = when {
                    reserve.status == ReserveStatus.RELEASED -> ReserveStatus.RELEASED
                    targetAmount <= reserve.appliedAmountCents -> ReserveStatus.APPLIED
                    else -> ReserveStatus.RESERVED
                  }
                )
              )
            }
            transactionDao.update(
              tx.copy(
                title = effectiveTitle,
                amountCents = targetAmount,
                date = effectiveDate,
                notes = effectiveNotes,
                isCorrected = true,
                correctionReason = reason
              )
            )
          }
        }

        TransactionType.ADJUSTMENT -> {
          if (!voidTransaction && newAmountCents != null && newAmountCents != tx.amountCents) {
            throw IllegalArgumentException(
              "Cannot change the amount of an adjustment directly. Void & revert it instead."
            )
          }
          if (voidTransaction && tx.fundedFromReserveCents > 0L) {
            when (tx.category) {
              "Emergency Withdrawal" ->
                restoreReserves(ReserveType.EMERGENCY_SAVINGS, tx.fundedFromReserveCents)
              "Reserve Release" -> {
                val targetRes = tx.reserveId?.let { reserveDao.getById(it) }
                if (targetRes != null) {
                  val restoredApplied = (targetRes.appliedAmountCents - tx.fundedFromReserveCents).coerceAtLeast(0L)
                  reserveDao.update(
                    targetRes.copy(
                      appliedAmountCents = restoredApplied,
                      status = if (restoredApplied < targetRes.amountCents) ReserveStatus.RESERVED else ReserveStatus.APPLIED
                    )
                  )
                }
              }
            }
          }
          transactionDao.update(
            tx.copy(
              title = effectiveTitle,
              amountCents = tx.amountCents,
              date = effectiveDate,
              notes = effectiveNotes,
              isVoided = voidTransaction,
              isCorrected = true,
              correctionReason = reason
            )
          )
        }
      }
    }
  }

  suspend fun createBackupPayload(): BaonPlanBackupPayload {
    return BaonPlanBackupPayload(
      settings = settingsDao.getSettings() ?: DefaultSettingsProvider.defaultSettings(),
      debts = debtDao.getAll(),
      installments = installmentDao.getAll(),
      transactions = transactionDao.getAll(),
      allowanceRecords = allowanceDao.getAll(),
      expenses = expenseDao.getAll(),
      debtReserves = reserveDao.getAll(),
      debtAttachments = attachmentDao.getAll(),
      budgetProfile = budgetProfileDao.getProfile(),
      incomeSources = incomeSourceDao.getAll(),
      incomeOccurrences = incomeOccurrenceDao.getAll(),
      budgetAllocations = budgetAllocationDao.getAll()
    )
  }

  suspend fun markBackupExportedNow() {
    prefs.setLastBackupEpochMillis(ManilaTime.nowMillis())
  }

  suspend fun restoreFromBackup(payload: BaonPlanBackupPayload) {
    db.withTransaction {
      attachmentDao.deleteAll()
      installmentDao.deleteAll()
      transactionDao.deleteAll()
      allowanceDao.deleteAll()
      expenseDao.deleteAll()
      reserveDao.deleteAll()
      incomeOccurrenceDao.deleteAll()
      incomeSourceDao.deleteAll()
      budgetAllocationDao.deleteAll()
      budgetProfileDao.deleteAll()
      debtDao.deleteAll()
      settingsDao.deleteAll()

      settingsDao.upsert(payload.settings.copy(id = 1))
      if (payload.debts.isNotEmpty()) debtDao.insertAll(payload.debts)
      if (payload.installments.isNotEmpty()) installmentDao.insertAll(payload.installments)
      if (payload.transactions.isNotEmpty()) transactionDao.insertAll(payload.transactions)
      if (payload.allowanceRecords.isNotEmpty()) allowanceDao.insertAll(payload.allowanceRecords)
      if (payload.expenses.isNotEmpty()) expenseDao.insertAll(payload.expenses)
      if (payload.debtReserves.isNotEmpty()) reserveDao.insertAll(payload.debtReserves)
      if (payload.incomeSources.isNotEmpty()) incomeSourceDao.insertAll(payload.incomeSources)
      val sourceIds = payload.incomeSources.mapTo(HashSet()) { it.id }
      val cleanOccurrences = payload.incomeOccurrences.map { occ ->
        if (occ.sourceId != null && occ.sourceId !in sourceIds) occ.copy(sourceId = null) else occ
      }
      if (cleanOccurrences.isNotEmpty()) incomeOccurrenceDao.insertAll(cleanOccurrences)
      if (payload.budgetAllocations.isNotEmpty()) budgetAllocationDao.insertAll(payload.budgetAllocations)
      payload.budgetProfile?.let { budgetProfileDao.upsert(it.copy(id = 1)) }
      val debtIds = payload.debts.mapTo(HashSet()) { it.id }
      val validAttachments = payload.debtAttachments.filter { it.debtId in debtIds }
      if (validAttachments.isNotEmpty()) attachmentDao.insertAll(validAttachments)
    }
    deleteAllStoredAttachments()
    prefs.clearOperationalPrefs()
  }
}
