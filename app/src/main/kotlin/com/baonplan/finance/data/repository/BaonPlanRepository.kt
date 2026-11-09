package com.baonplan.finance.data.repository

import androidx.room.withTransaction
import com.baonplan.finance.data.backup.BaonPlanBackupPayload
import com.baonplan.finance.data.local.AppPreferencesState
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.SettingsPreferencesDataStore
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.ExpensePreset
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.ThemeMode
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import kotlin.math.ceil

class BaonPlanRepository(
  private val db: BaonPlanDatabase,
  private val prefs: SettingsPreferencesDataStore
) {
  private val debtDao = db.debtDao()
  private val installmentDao = db.installmentDao()
  private val transactionDao = db.transactionDao()
  private val allowanceDao = db.allowanceRecordDao()
  private val expenseDao = db.expenseDao()
  private val reserveDao = db.debtReserveDao()
  private val settingsDao = db.userSettingsDao()

  val debtsFlow: Flow<List<DebtEntity>> = debtDao.observeAll()
  val installmentsFlow: Flow<List<InstallmentEntity>> = installmentDao.observeAll()
  val transactionsFlow: Flow<List<TransactionEntity>> = transactionDao.observeAll()
  val allowancesFlow: Flow<List<AllowanceRecordEntity>> = allowanceDao.observeAll()
  val expensesFlow: Flow<List<ExpenseEntity>> = expenseDao.observeAll()
  val reservesFlow: Flow<List<DebtReserveEntity>> = reserveDao.observeAll()
  val settingsFlow: Flow<UserSettingsEntity> = settingsDao.observeSettings().map {
    it ?: DefaultSettingsProvider.defaultSettings()
  }
  val appPreferencesFlow: Flow<AppPreferencesState> = prefs.preferencesFlow

  fun observeDebt(debtId: Long): Flow<DebtEntity?> = debtDao.observeById(debtId)
  fun observeDebtInstallments(debtId: Long): Flow<List<InstallmentEntity>> =
    installmentDao.observeByDebtId(debtId)
  fun observeDebtTransactions(debtId: Long): Flow<List<TransactionEntity>> =
    transactionDao.observeByDebtId(debtId)

  suspend fun ensureInitialized() {
    if (settingsDao.getSettings() == null) {
      settingsDao.upsert(DefaultSettingsProvider.defaultSettings())
    }
  }

  suspend fun clearAllData() {
    db.withTransaction {
      installmentDao.deleteAll()
      transactionDao.deleteAll()
      allowanceDao.deleteAll()
      expenseDao.deleteAll()
      reserveDao.deleteAll()
      debtDao.deleteAll()
      settingsDao.upsert(DefaultSettingsProvider.defaultSettings())
    }
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
      allowanceDao.deleteById(allowanceId)
      val linkedTx = transactionDao.getAll().firstOrNull { it.allowanceId == allowanceId && !it.isVoided }
      if (linkedTx != null) {
        transactionDao.update(
          linkedTx.copy(isVoided = true, isCorrected = true, correctionReason = "Allowance record removed")
        )
      }
    }
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

  suspend fun deleteDebt(debtId: Long) {
    db.withTransaction {
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

      val existing = installmentDao.getByDebtId(debtId)
      val unpaid = existing.filter { it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL }
      if (generateFullSchedule && updatedDebt.frequency != RepaymentFrequency.UNKNOWN) {
        val count = updatedDebt.remainingInstallmentsCount.takeIf { it > 0 }
          ?: if (updatedDebt.installmentAmountCents > 0L) {
            ceil(updatedDebt.remainingBalanceCents.toDouble() / updatedDebt.installmentAmountCents.toDouble()).toInt().coerceAtLeast(1)
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
        installmentDao.deleteUnpaidByDebtId(debtId)
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
              notes = "Confirmed schedule"
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
              notes = "User confirmed due date"
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
    if (frequency == RepaymentFrequency.UNKNOWN) return false
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

      val paidCount = installmentDao.getByDebtId(debtId).count { it.status == InstallmentStatus.PAID }
      installmentDao.deleteUnpaidByDebtId(debtId)
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
            notes = "Generated schedule (${frequency.label})"
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
      if (inst.status == InstallmentStatus.PAID) return@withTransaction null
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

    // Apply payment cleanly to pending installments
    val allInst = installmentDao.getByDebtId(debtId)
    val pendingInst = allInst.filter {
      it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
    }
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

    val updatedInstallments = installmentDao.getByDebtId(debtId)
    val nextUnpaid = updatedInstallments.firstOrNull {
      it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
    }

    val nextRemainingInstallments = when {
      newRemainingBalance == 0L -> 0
      updatedInstallments.any { it.status != InstallmentStatus.PAID } &&
        updatedInstallments.count { it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL } >= debt.remainingInstallmentsCount ->
        updatedInstallments.count { it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL }
      debt.remainingInstallmentsCount > 0 -> {
        if (completedInstCount > 0) {
          (debt.remainingInstallmentsCount - completedInstCount).coerceAtLeast(1)
        } else if (debt.installmentAmountCents > 0L) {
          ceil(newRemainingBalance.toDouble() / debt.installmentAmountCents.toDouble()).toInt().coerceAtLeast(1)
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
    } else if (debt.nextDueDate != null && debt.frequency != RepaymentFrequency.UNKNOWN) {
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
              val allInstBeforeRevert = installmentDao.getByDebtId(debt.id)
              val primaryLinked = allInstBeforeRevert
                .filter { it.linkedTransactionId == tx.id || it.id == tx.installmentId }
                .sortedByDescending { it.installmentNumber }
              val fallbackLinked = allInstBeforeRevert
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

              val allInstAfterRevert = installmentDao.getByDebtId(debt.id)
              val firstUnpaidAfterRevert = allInstAfterRevert.firstOrNull {
                it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
              }
              val unpaidCountAfterRevert = allInstAfterRevert.count {
                it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
              }

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
                  ceil(restoredRemaining.toDouble() / debt.installmentAmountCents.toDouble()).toInt().coerceAtLeast(1)
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
              val allInstBefore = installmentDao.getByDebtId(debt.id)
              if (delta < 0L) {
                var toRevert = -delta
                val primary = allInstBefore
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
                val pending = allInstBefore.filter {
                  it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
                }
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

              val allInstAfter = installmentDao.getByDebtId(debt.id)
              val firstUnpaid = allInstAfter.firstOrNull {
                it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
              }
              val unpaidCount = allInstAfter.count {
                it.status == InstallmentStatus.UPCOMING || it.status == InstallmentStatus.PARTIAL
              }

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
                  ceil(newRemaining.toDouble() / debt.installmentAmountCents.toDouble()).toInt().coerceAtLeast(1)
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
          if (voidTransaction) {
            if (record != null) allowanceDao.deleteById(record.id)
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
      debtReserves = reserveDao.getAll()
    )
  }

  suspend fun markBackupExportedNow() {
    prefs.setLastBackupEpochMillis(ManilaTime.nowMillis())
  }

  suspend fun restoreFromBackup(payload: BaonPlanBackupPayload) {
    db.withTransaction {
      installmentDao.deleteAll()
      transactionDao.deleteAll()
      allowanceDao.deleteAll()
      expenseDao.deleteAll()
      reserveDao.deleteAll()
      debtDao.deleteAll()
      settingsDao.deleteAll()

      settingsDao.upsert(payload.settings.copy(id = 1))
      if (payload.debts.isNotEmpty()) debtDao.insertAll(payload.debts)
      if (payload.installments.isNotEmpty()) installmentDao.insertAll(payload.installments)
      if (payload.transactions.isNotEmpty()) transactionDao.insertAll(payload.transactions)
      if (payload.allowanceRecords.isNotEmpty()) allowanceDao.insertAll(payload.allowanceRecords)
      if (payload.expenses.isNotEmpty()) expenseDao.insertAll(payload.expenses)
      if (payload.debtReserves.isNotEmpty()) reserveDao.insertAll(payload.debtReserves)
    }
    prefs.clearOperationalPrefs()
  }
}
