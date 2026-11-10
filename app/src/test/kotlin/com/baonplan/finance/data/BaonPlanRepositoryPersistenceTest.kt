package com.baonplan.finance.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.baonplan.finance.data.backup.BackupSerializer
import com.baonplan.finance.data.local.BaonPlanDatabase
import com.baonplan.finance.data.local.SettingsPreferencesDataStore
import com.baonplan.finance.data.local.entity.BudgetAllocationEntity
import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.repository.BaonPlanRepository
import com.baonplan.finance.data.repository.RetainedAttachmentSource
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.budget.BudgetRecurrenceEngine
import com.baonplan.finance.domain.budget.CashFlowBudgetEngine
import com.baonplan.finance.domain.budget.PaymentGroupingEngine
import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.ExpenseCategory
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import com.baonplan.finance.domain.model.TransactionType
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BaonPlanRepositoryPersistenceTest {

  private lateinit var context: Context
  private lateinit var dbFile: File
  private lateinit var db: BaonPlanDatabase
  private lateinit var prefs: SettingsPreferencesDataStore
  private lateinit var repo: BaonPlanRepository

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    dbFile = File(context.cacheDir, "test_baonplan_${System.nanoTime()}.db")
    db = Room.databaseBuilder(context, BaonPlanDatabase::class.java, dbFile.absolutePath)
      .allowMainThreadQueries()
      .build()
    prefs = SettingsPreferencesDataStore(context)
    repo = BaonPlanRepository(db, prefs)
  }

  @After
  fun tearDown() {
    if (db.isOpen) db.close()
    dbFile.delete()
  }

  @Test
  fun firstLaunch_ensureInitializedStartsWithZeroLoansAndDefaultSettings() = runTest {
    repo.ensureInitialized()

    assertEquals(0, db.debtDao().getAll().size)
    assertEquals(0, db.installmentDao().getAll().size)
    assertEquals(0, db.transactionDao().getAll().size)
    assertEquals(0L, FinancialCalculator.calculateTotalActiveDebt(db.debtDao().getAll()))

    val settings = db.userSettingsDao().getSettings()
    assertNotNull(settings)
    assertEquals(40_000L, settings!!.minWeeklyAllowanceCents)
    assertEquals(50_000L, settings.maxWeeklyAllowanceCents)
    assertEquals(10_000L, settings.weeklyFoodBudgetCents)
  }

  @Test
  fun partialRepaymentsAndMarkPaid_neverDoubleCountAndCorrectRevertsProperly() = runTest {
    repo.ensureInitialized()

    val debtId = repo.saveDebt(
      DebtEntity(
        name = "Sample Weekly Loan",
        lender = "Sample Lender",
        category = "Cash Loan",
        remainingBalanceCents = 44_455L,
        installmentAmountCents = 2_615L,
        remainingInstallmentsCount = 17,
        frequency = RepaymentFrequency.WEEKLY,
        nextDueDate = "2026-11-16",
        dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
      )
    )
    assertTrue(
      repo.generateInstallmentSchedule(
        debtId = debtId,
        startDateIso = "2026-11-16",
        count = 17,
        installmentAmountCents = 2_615L,
        frequency = RepaymentFrequency.WEEKLY,
        customIntervalDays = null,
        dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
      )
    )
    val firstInstId = db.installmentDao().getByDebtId(debtId).first().id

    // Partial payment of ₱10.00 (1000 cents) on first ₱26.15 installment
    val tx1 = repo.recordRepayment(
      debtId = debtId,
      amountCents = 1_000L,
      dateIso = "2026-11-16",
      targetInstallmentId = firstInstId
    )
    assertNotNull(tx1)

    val afterPartial = db.debtDao().getById(debtId)!!
    assertEquals(43_455L, afterPartial.remainingBalanceCents)
    assertEquals(1_000L, afterPartial.trackedPaidCents)
    val instAfterPartial = db.installmentDao().getById(firstInstId)!!
    assertEquals(1_000L, instAfterPartial.paidAmountCents)
    assertEquals(InstallmentStatus.PARTIAL, instAfterPartial.status)

    // Marking the installment paid now pays ONLY the remaining ₱16.15 (1615 cents)
    val tx2 = repo.markInstallmentPaid(installmentId = firstInstId, dateIso = "2026-11-16")
    assertNotNull(tx2)
    val afterInst = db.debtDao().getById(debtId)!!
    assertEquals(41_840L, afterInst.remainingBalanceCents) // 44455 - 2615 = 41840
    assertEquals(2_615L, afterInst.trackedPaidCents)
    assertEquals(InstallmentStatus.PAID, db.installmentDao().getById(firstInstId)!!.status)

    // Calling markInstallmentPaid a second time MUST do nothing (no double-counting!)
    val duplicate = repo.markInstallmentPaid(installmentId = firstInstId, dateIso = "2026-11-16")
    assertNull(duplicate)
    assertEquals(41_840L, db.debtDao().getById(debtId)!!.remainingBalanceCents)

    // Correct tx1 from ₱10.00 -> ₱15.00 (+500 cents delta)
    repo.correctOrVoidTransaction(
      transactionId = tx1!!,
      newAmountCents = 1_500L,
      voidTransaction = false,
      reason = "Typo"
    )
    assertEquals(41_340L, db.debtDao().getById(debtId)!!.remainingBalanceCents)

    // Void tx2 (₱16.15) -> restores ₱16.15 to remaining balance
    repo.correctOrVoidTransaction(
      transactionId = tx2!!,
      voidTransaction = true,
      reason = "Mistaken duplicate"
    )
    assertEquals(42_955L, db.debtDao().getById(debtId)!!.remainingBalanceCents)
    assertTrue(db.transactionDao().getById(tx2)!!.isVoided)
  }

  @Test
  fun reserveDoesNotReduceDebtUntilRepaymentAndPersistsAcrossDatabaseRestart() = runTest {
    repo.ensureInitialized()

    val bnplId = repo.saveDebt(
      DebtEntity(
        name = "Sample BNPL",
        lender = "Sample Marketplace",
        category = "BNPL",
        totalScheduledRepaymentCents = 22_917L,
        remainingBalanceCents = 22_917L,
        installmentAmountCents = 22_917L,
        remainingInstallmentsCount = 1,
        frequency = RepaymentFrequency.ONE_TIME,
        nextDueDate = "2026-12-15",
        dateStatus = DueDateStatus.CONFIRMED
      )
    )
    val otherLoanId = repo.saveDebt(
      DebtEntity(
        name = "Other Loan",
        lender = "Sample Lender",
        category = "Cash Loan",
        remainingBalanceCents = 30_000L,
        installmentAmountCents = 10_000L,
        remainingInstallmentsCount = 3,
        frequency = RepaymentFrequency.MONTHLY,
        nextDueDate = "2026-12-20",
        dateStatus = DueDateStatus.CONFIRMED
      )
    )

    // Create a ₱150.00 debt reserve
    repo.createReserve(
      reserveType = ReserveType.DEBT_REPAYMENT_RESERVE,
      amountCents = 15_000L,
      dateIso = "2026-11-09",
      targetDebtId = bnplId
    )

    // Total debt MUST remain unchanged until repayment is actually recorded!
    assertEquals(52_917L, FinancialCalculator.calculateTotalActiveDebt(db.debtDao().getAll()))

    // Pay Sample BNPL (₱229.17) using the ₱150.00 debt reserve + cash remainder
    repo.recordRepayment(
      debtId = bnplId,
      amountCents = 22_917L,
      dateIso = "2026-12-15",
      useDebtReserve = true
    )

    val bnpl = db.debtDao().getById(bnplId)!!
    assertEquals(0L, bnpl.remainingBalanceCents)
    assertEquals(DebtStatus.PAID, bnpl.status)
    assertEquals(ReserveStatus.APPLIED, db.debtReserveDao().getAll().first().status)

    // Simulate app close / process kill / device reboot by closing and reopening SQLite file
    db.close()
    val reopenedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, dbFile.absolutePath)
      .allowMainThreadQueries()
      .build()
    try {
      val reopenedDebts = reopenedDb.debtDao().getAll()
      assertEquals(2, reopenedDebts.size)
      assertEquals(0L, reopenedDb.debtDao().getById(bnplId)!!.remainingBalanceCents)
      assertEquals(DebtStatus.PAID, reopenedDb.debtDao().getById(bnplId)!!.status)
      assertEquals(30_000L, FinancialCalculator.calculateTotalActiveDebt(reopenedDebts))
      assertEquals(otherLoanId, reopenedDebts.first { it.status == DebtStatus.ACTIVE }.id)
    } finally {
      reopenedDb.close()
    }
  }

  @Test
  fun clearAllData_persistsEmptyStateAcrossSimulatedRestart() = runTest {
    repo.ensureInitialized()
    repo.saveDebt(
      DebtEntity(
        name = "Loan to Clear",
        lender = "Sample Lender",
        category = "Cash Loan",
        remainingBalanceCents = 10_000L
      )
    )
    assertEquals(1, db.debtDao().getAll().size)

    repo.clearAllData()
    assertEquals(0, db.debtDao().getAll().size)
    assertEquals(0, db.installmentDao().getAll().size)
    assertNotNull(db.userSettingsDao().getSettings())

    // Simulate app restart: close Room, open again, run ensureInitialized()
    db.close()
    val restartedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, dbFile.absolutePath)
      .allowMainThreadQueries()
      .build()
    try {
      val restartedRepo = BaonPlanRepository(restartedDb, prefs)
      restartedRepo.ensureInitialized()

      assertEquals(0, restartedDb.debtDao().getAll().size)
      assertEquals(0, restartedDb.installmentDao().getAll().size)
      assertNotNull(restartedDb.userSettingsDao().getSettings())
    } finally {
      restartedDb.close()
    }
  }

  @Test
  fun allowanceExpenseReserveCorrections_syncUnderlyingEntitiesAndPersist() = runTest {
    repo.ensureInitialized()

    repo.recordAllowance(
      amountCents = 40_000L,
      receivedDateIso = "2026-11-09",
      sourceLabel = "Weekly Baon"
    )
    repo.recordExpense(
      title = "Campus Lunch",
      amountCents = 5_000L,
      dateIso = "2026-11-09",
      category = ExpenseCategory.SCHOOL_FOOD,
      isSchoolDay = true
    )
    repo.createReserve(
      reserveType = ReserveType.EMERGENCY_SAVINGS,
      amountCents = 5_000L,
      dateIso = "2026-11-09"
    )

    val txs = db.transactionDao().getAll()
    val allowanceTx = txs.first { it.allowanceId != null }
    val expenseTx = txs.first { it.expenseId != null }
    val reserveTx = txs.first { it.reserveId != null }

    // Edit allowance from ₱400 to ₱500
    repo.correctOrVoidTransaction(
      transactionId = allowanceTx.id,
      newAmountCents = 50_000L,
      reason = "Received ₱500 this week"
    )
    assertEquals(50_000L, db.allowanceRecordDao().getById(allowanceTx.allowanceId!!)!!.amountCents)

    // Edit expense from ₱50 to ₱65
    repo.correctOrVoidTransaction(
      transactionId = expenseTx.id,
      newAmountCents = 6_500L,
      reason = "Added bottled water"
    )
    assertEquals(6_500L, db.expenseDao().getById(expenseTx.expenseId!!)!!.amountCents)

    // Void reserve -> sets reserve status to RELEASED
    repo.correctOrVoidTransaction(
      transactionId = reserveTx.id,
      voidTransaction = true,
      reason = "Needed cash for school project"
    )
    assertEquals(ReserveStatus.RELEASED, db.debtReserveDao().getById(reserveTx.reserveId!!)!!.status)

    // Reopen database to verify full SQLite persistence
    db.close()
    val reopenedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, dbFile.absolutePath)
      .allowMainThreadQueries()
      .build()
    try {
      assertEquals(50_000L, reopenedDb.allowanceRecordDao().getAll().single().amountCents)
      assertEquals(6_500L, reopenedDb.expenseDao().getAll().single().amountCents)
      assertEquals(ReserveStatus.RELEASED, reopenedDb.debtReserveDao().getAll().single().status)
    } finally {
      reopenedDb.close()
    }
  }

  @Test
  fun confirmDebtDateAndUnknownFrequencyRefusal_respectConfirmedVsUnknownRules() = runTest {
    repo.ensureInitialized()

    val biweeklyLoanId = repo.saveDebt(
      DebtEntity(
        name = "Sample Biweekly Loan",
        lender = "Sample Lender",
        category = "E-Wallet",
        remainingBalanceCents = 33_507L,
        installmentAmountCents = 11_169L,
        remainingInstallmentsCount = 3,
        frequency = RepaymentFrequency.EVERY_14_DAYS,
        dateStatus = DueDateStatus.UNCONFIRMED
      )
    )
    val unknownLoanId = repo.saveDebt(
      DebtEntity(
        name = "Sample Unknown Loan",
        lender = "Sample Lender",
        category = "Cash Loan",
        remainingBalanceCents = 108_738L,
        installmentAmountCents = 5_178L,
        remainingInstallmentsCount = 21,
        frequency = RepaymentFrequency.UNKNOWN,
        dateStatus = DueDateStatus.UNKNOWN
      )
    )

    // Confirm biweekly loan (3 x ₱111.69, EVERY_14_DAYS) with full schedule
    repo.confirmDebtDate(
      debtId = biweeklyLoanId,
      confirmedDateIso = "2026-11-20",
      generateFullSchedule = true
    )
    val confirmedLoan = db.debtDao().getById(biweeklyLoanId)!!
    assertEquals("2026-11-20", confirmedLoan.nextDueDate)
    assertEquals(DueDateStatus.CONFIRMED, confirmedLoan.dateStatus)
    val schedule = db.installmentDao().getByDebtId(biweeklyLoanId)
    assertEquals(3, schedule.size)
    assertEquals("2026-11-20", schedule[0].dueDate)
    assertEquals("2026-12-04", schedule[1].dueDate)
    assertEquals("2026-12-18", schedule[2].dueDate)
    assertEquals(33_507L, schedule.sumOf { it.amountDueCents })

    // Unknown frequency loan MUST refuse automatic schedule generation
    val generatedForUnknown = repo.generateInstallmentSchedule(
      debtId = unknownLoanId,
      startDateIso = "2026-11-20",
      count = 21,
      installmentAmountCents = 5_178L,
      frequency = RepaymentFrequency.UNKNOWN,
      customIntervalDays = null,
      dateStatus = DueDateStatus.UNKNOWN
    )
    assertFalse(generatedForUnknown)
    assertTrue(db.installmentDao().getByDebtId(unknownLoanId).isEmpty())
    assertNull(db.debtDao().getById(unknownLoanId)!!.nextDueDate)
  }

  @Test
  fun saveImportedDebt_irregularScheduleAndAttachment_persistsAndCleansUp() = runTest {
    val attachmentDir = File(context.cacheDir, "test_attachments_${System.nanoTime()}")
    val repoWithAttachments = BaonPlanRepository(db, prefs, attachmentDir)
    repoWithAttachments.ensureInitialized()

    val tempScanFile = File(context.cacheDir, "scan_tmp_${System.nanoTime()}.jpg").apply {
      writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    }

    try {
      val importedDebt = DebtEntity(
        name = "Atome Irregular Plan",
        lender = "Atome",
        category = "BNPL",
        originalPrincipalCents = 150_000L,
        totalScheduledRepaymentCents = 150_000L,
        priorPaidCents = 40_000L,
        isPriorPaidKnown = true,
        remainingBalanceCents = 110_000L,
        installmentAmountCents = 35_000L,
        remainingInstallmentsCount = 3,
        frequency = RepaymentFrequency.IRREGULAR,
        nextDueDate = "2026-01-19",
        dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
      )
      val rows = listOf(
        InstallmentEntity(
          debtId = 0L,
          installmentNumber = 1,
          dueDate = "2026-01-05",
          amountDueCents = 40_000L,
          paidAmountCents = 40_000L,
          dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
          status = InstallmentStatus.PAID,
          paidDate = "2026-01-05"
        ),
        InstallmentEntity(
          debtId = 0L,
          installmentNumber = 2,
          dueDate = "2026-01-19",
          amountDueCents = 35_000L,
          dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
          status = InstallmentStatus.UPCOMING
        ),
        InstallmentEntity(
          debtId = 0L,
          installmentNumber = 3,
          dueDate = "2026-02-02",
          amountDueCents = 35_000L,
          dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
          status = InstallmentStatus.UPCOMING
        ),
        InstallmentEntity(
          debtId = 0L,
          installmentNumber = 4,
          dueDate = "2026-02-27",
          amountDueCents = 40_000L,
          dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
          status = InstallmentStatus.UPCOMING
        )
      )

      val newDebtId = repoWithAttachments.saveImportedDebtWithSchedule(
        debt = importedDebt,
        scheduleRows = rows,
        attachmentSources = listOf(
          RetainedAttachmentSource(
            sourceFile = tempScanFile,
            displayLabel = "Screenshot #1",
            screenshotIndex = 0
          )
        )
      )

      val savedDebt = db.debtDao().getById(newDebtId)!!
      assertEquals(0L, savedDebt.trackedPaidCents)
      assertEquals(40_000L, savedDebt.priorPaidCents)
      assertEquals("2026-01-19", savedDebt.nextDueDate)

      val savedRows = db.installmentDao().getByDebtId(newDebtId)
      assertEquals(4, savedRows.size)
      assertTrue(savedRows.all { it.linkedTransactionId == null })

      val breakdown = FinancialCalculator.calculateLoanCostBreakdown(savedDebt)
      assertEquals(40_000L, breakdown.totalKnownPaidCents)

      // Payoff insights MUST include dated irregular loan without fabricating dates
      val today = LocalDate.of(2026, 1, 10)
      val overview = FinancialCalculator.buildPaymentOverview(listOf(savedDebt), savedRows, today)
      val payoff = FinancialCalculator.calculatePayoffInsights(listOf(savedDebt), savedRows, overview, today)
      assertEquals(1, payoff.includedDebtsCount)
      assertEquals("2026-02-27", payoff.estimatedDebtFreeDateIso)

      // Confirming next date preserves irregular extracted rows instead of wiping schedule
      repoWithAttachments.confirmDebtDate(newDebtId, "2026-01-19", generateFullSchedule = true)
      val afterConfirmRows = db.installmentDao().getByDebtId(newDebtId)
      assertEquals(listOf("2026-01-05", "2026-01-19", "2026-02-02", "2026-02-27"), afterConfirmRows.map { it.dueDate })

      // Opted-in attachment copied to private dir & cleaned on deleteDebt
      val storedAttachments = db.debtAttachmentDao().getByDebtId(newDebtId)
      assertEquals(1, storedAttachments.size)
      val storedFile = repoWithAttachments.resolveAttachmentFile(storedAttachments.single().fileName)!!
      assertTrue(storedFile.exists())

      repoWithAttachments.deleteDebt(newDebtId)
      assertTrue(db.debtAttachmentDao().getByDebtId(newDebtId).isEmpty())
      assertFalse(storedFile.exists())
    } finally {
      tempScanFile.delete()
      attachmentDir.deleteRecursively()
    }
  }

  @Test
  fun migration1To2_preservesV1RecordsAndEnablesDebtAttachmentsWithCascadeDelete() = runTest {
    val v1DbFile = File(context.cacheDir, "migration_v1_${System.nanoTime()}.db")
    try {
      val openHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(v1DbFile.absolutePath)
          .callback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lender` TEXT NOT NULL, `category` TEXT NOT NULL, `originalPrincipalCents` INTEGER, `amountReceivedCents` INTEGER, `interestCents` INTEGER, `serviceFeeCents` INTEGER, `processingFeeCents` INTEGER, `totalScheduledRepaymentCents` INTEGER, `priorPaidCents` INTEGER NOT NULL, `isPriorPaidKnown` INTEGER NOT NULL, `trackedPaidCents` INTEGER NOT NULL, `remainingBalanceCents` INTEGER NOT NULL, `installmentAmountCents` INTEGER NOT NULL, `remainingInstallmentsCount` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `customIntervalDays` INTEGER, `lastKnownPaidDate` TEXT, `nextDueDate` TEXT, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `installmentNumber` INTEGER NOT NULL, `dueDate` TEXT, `amountDueCents` INTEGER NOT NULL, `paidAmountCents` INTEGER NOT NULL, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `paidDate` TEXT, `linkedTransactionId` INTEGER, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_debtId` ON `installments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `date` TEXT NOT NULL, `debtId` INTEGER, `installmentId` INTEGER, `reserveId` INTEGER, `expenseId` INTEGER, `allowanceId` INTEGER, `fundedFromReserveCents` INTEGER NOT NULL, `category` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `isCorrected` INTEGER NOT NULL, `isVoided` INTEGER NOT NULL, `correctionReason` TEXT, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_debtId` ON `transactions` (`debtId`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_installmentId` ON `transactions` (`installmentId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `allowance_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `receivedDate` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `sourceLabel` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_allowance_records_weekStartDate` ON `allowance_records` (`weekStartDate`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `category` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `isSchoolDay` INTEGER NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_reserves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `targetDebtId` INTEGER, `reserveType` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `appliedAmountCents` INTEGER NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_weekStartDate` ON `debt_reserves` (`weekStartDate`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_targetDebtId` ON `debt_reserves` (`targetDebtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`id` INTEGER NOT NULL, `minWeeklyAllowanceCents` INTEGER NOT NULL, `maxWeeklyAllowanceCents` INTEGER NOT NULL, `conservativeAllowanceCents` INTEGER NOT NULL, `schoolDaysPerWeek` INTEGER NOT NULL, `foodPerSchoolDayCents` INTEGER NOT NULL, `weeklyFoodBudgetCents` INTEGER NOT NULL, `weeklyOtherExpensesBudgetCents` INTEGER NOT NULL, `weeklyEmergencySavingsTargetCents` INTEGER NOT NULL, `weeklyDebtReserveTargetCents` INTEGER NOT NULL, `remindersEnabled` INTEGER NOT NULL, `allowanceReminderDayOfWeek` INTEGER NOT NULL, `reminderHour` INTEGER NOT NULL, `reminderMinute` INTEGER NOT NULL, `isManuallyEntered` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
          })
          .build()
      )
      openHelper.setWriteAheadLoggingEnabled(false)

      openHelper.writableDatabase.use { raw ->
        raw.execSQL(
          "INSERT INTO `debts` (`id`, `name`, `lender`, `category`, `priorPaidCents`, `isPriorPaidKnown`, `trackedPaidCents`, `remainingBalanceCents`, `installmentAmountCents`, `remainingInstallmentsCount`, `frequency`, `dateStatus`, `status`, `isManuallyEntered`, `notes`, `createdAtEpochMillis`, `updatedAtEpochMillis`) " +
            "VALUES (7, 'Legacy v1 Loan', 'BillEase', 'BNPL', 0, 0, 0, 120000, 40000, 3, 'MONTHLY', 'CONFIRMED', 'ACTIVE', 1, '', 1000, 1000)"
        )
      }
      openHelper.close()

      val migratedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, v1DbFile.absolutePath)
        .addMigrations(
          BaonPlanDatabase.MIGRATION_1_2,
          BaonPlanDatabase.MIGRATION_2_3,
          BaonPlanDatabase.MIGRATION_3_4,
          BaonPlanDatabase.MIGRATION_4_5
        )
        .allowMainThreadQueries()
        .build()
      try {
        val migratedDebt = migratedDb.debtDao().getById(7L)!!
        assertEquals("Legacy v1 Loan", migratedDebt.name)
        assertEquals(120_000L, migratedDebt.remainingBalanceCents)
        assertEquals(
          com.baonplan.finance.domain.model.LoanProductClassification.UNKNOWN,
          migratedDebt.productClassification
        )
        assertEquals(
          com.baonplan.finance.domain.model.ObligationRole.STANDALONE,
          migratedDebt.obligationRole
        )

        migratedDb.debtAttachmentDao().insertAll(
          listOf(
            DebtAttachmentEntity(
              debtId = 7L,
              fileName = "debt_7_s0.jpg",
              displayLabel = "Screenshot #1",
              screenshotIndex = 0,
              createdAtEpochMillis = 2000L
            )
          )
        )
        assertEquals(1, migratedDb.debtAttachmentDao().getByDebtId(7L).size)

        migratedDb.debtDao().deleteById(7L)
        assertTrue(migratedDb.debtAttachmentDao().getByDebtId(7L).isEmpty())
      } finally {
        migratedDb.close()
      }
    } finally {
      v1DbFile.delete()
    }
  }

  @Test
  fun migration2To3_preservesV2RecordsAndSupportsLinkedChildObligationsWithoutDoubleCounting() = runTest {
    val v2DbFile = File(context.cacheDir, "migration_v2_${System.nanoTime()}.db")
    try {
      val openHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(v2DbFile.absolutePath)
          .callback(object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lender` TEXT NOT NULL, `category` TEXT NOT NULL, `originalPrincipalCents` INTEGER, `amountReceivedCents` INTEGER, `interestCents` INTEGER, `serviceFeeCents` INTEGER, `processingFeeCents` INTEGER, `totalScheduledRepaymentCents` INTEGER, `priorPaidCents` INTEGER NOT NULL, `isPriorPaidKnown` INTEGER NOT NULL, `trackedPaidCents` INTEGER NOT NULL, `remainingBalanceCents` INTEGER NOT NULL, `installmentAmountCents` INTEGER NOT NULL, `remainingInstallmentsCount` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `customIntervalDays` INTEGER, `lastKnownPaidDate` TEXT, `nextDueDate` TEXT, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `installmentNumber` INTEGER NOT NULL, `dueDate` TEXT, `amountDueCents` INTEGER NOT NULL, `paidAmountCents` INTEGER NOT NULL, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `paidDate` TEXT, `linkedTransactionId` INTEGER, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_debtId` ON `installments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `displayLabel` TEXT NOT NULL, `screenshotIndex` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_attachments_debtId` ON `debt_attachments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `date` TEXT NOT NULL, `debtId` INTEGER, `installmentId` INTEGER, `reserveId` INTEGER, `expenseId` INTEGER, `allowanceId` INTEGER, `fundedFromReserveCents` INTEGER NOT NULL, `category` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `isCorrected` INTEGER NOT NULL, `isVoided` INTEGER NOT NULL, `correctionReason` TEXT, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_debtId` ON `transactions` (`debtId`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_installmentId` ON `transactions` (`installmentId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `allowance_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `receivedDate` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `sourceLabel` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_allowance_records_weekStartDate` ON `allowance_records` (`weekStartDate`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `category` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `isSchoolDay` INTEGER NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_reserves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `targetDebtId` INTEGER, `reserveType` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `appliedAmountCents` INTEGER NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_weekStartDate` ON `debt_reserves` (`weekStartDate`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_targetDebtId` ON `debt_reserves` (`targetDebtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`id` INTEGER NOT NULL, `minWeeklyAllowanceCents` INTEGER NOT NULL, `maxWeeklyAllowanceCents` INTEGER NOT NULL, `conservativeAllowanceCents` INTEGER NOT NULL, `schoolDaysPerWeek` INTEGER NOT NULL, `foodPerSchoolDayCents` INTEGER NOT NULL, `weeklyFoodBudgetCents` INTEGER NOT NULL, `weeklyOtherExpensesBudgetCents` INTEGER NOT NULL, `weeklyEmergencySavingsTargetCents` INTEGER NOT NULL, `weeklyDebtReserveTargetCents` INTEGER NOT NULL, `remindersEnabled` INTEGER NOT NULL, `allowanceReminderDayOfWeek` INTEGER NOT NULL, `reminderHour` INTEGER NOT NULL, `reminderMinute` INTEGER NOT NULL, `isManuallyEntered` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
          })
          .build()
      )
      openHelper.setWriteAheadLoggingEnabled(false)

      openHelper.writableDatabase.use { raw ->
        raw.execSQL(
          "INSERT INTO `debts` (`id`, `name`, `lender`, `category`, `priorPaidCents`, `isPriorPaidKnown`, `trackedPaidCents`, `remainingBalanceCents`, `installmentAmountCents`, `remainingInstallmentsCount`, `frequency`, `dateStatus`, `status`, `isManuallyEntered`, `notes`, `createdAtEpochMillis`, `updatedAtEpochMillis`) " +
            "VALUES (12, 'Existing v2 Credit Line', 'Maya', 'Credit Line', 0, 0, 0, 500000, 500000, 1, 'MONTHLY', 'CONFIRMED', 'ACTIVE', 1, '', 1000, 1000)"
        )
        raw.execSQL(
          "INSERT INTO `installments` (`id`, `debtId`, `installmentNumber`, `dueDate`, `amountDueCents`, `paidAmountCents`, `dateStatus`, `status`, `isManuallyEntered`, `notes`) " +
            "VALUES (99, 12, 1, '2026-12-05', 500000, 0, 'CONFIRMED', 'UPCOMING', 1, '')"
        )
      }
      openHelper.close()

      val migratedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, v2DbFile.absolutePath)
        .addMigrations(
          BaonPlanDatabase.MIGRATION_1_2,
          BaonPlanDatabase.MIGRATION_2_3,
          BaonPlanDatabase.MIGRATION_3_4,
          BaonPlanDatabase.MIGRATION_4_5
        )
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries()
        .build()
      try {
        val parent = migratedDb.debtDao().getById(12L)!!
        assertEquals("Existing v2 Credit Line", parent.name)
        assertEquals(500_000L, parent.remainingBalanceCents)
        assertEquals(1, parent.scheduleVersion)
        val migratedInst = migratedDb.installmentDao().getById(99L)!!
        assertEquals(1, migratedInst.scheduleVersion)
        assertNull(migratedInst.rawStatusLabel)

        // Insert a linked child billing statement and verify anti-double-counting
        migratedDb.debtDao().insert(
          DebtEntity(
            name = "Maya Nov Statement",
            lender = "Maya (Easy Credit)",
            category = "Billing Statement",
            productClassification = com.baonplan.finance.domain.model.LoanProductClassification.BNPL_PAY_LATER,
            obligationRole = com.baonplan.finance.domain.model.ObligationRole.BILLING_STATEMENT,
            parentDebtId = 12L,
            remainingBalanceCents = 300_000L,
            installmentAmountCents = 300_000L,
            remainingInstallmentsCount = 1,
            frequency = RepaymentFrequency.ONE_TIME
          )
        )
        val allDebts = migratedDb.debtDao().getAll()
        assertEquals(2, allDebts.size)
        assertEquals(500_000L, FinancialCalculator.calculateTotalActiveDebt(allDebts))
      } finally {
        migratedDb.close()
      }
    } finally {
      v2DbFile.delete()
    }
  }

  @Test
  fun migration3To4_andUpdateExistingDebt_preservesV1ScheduleHistoryAndPersistsV4Fields() = runTest {
    val v3DbFile = File(context.cacheDir, "migration_v3_${System.nanoTime()}.db")
    try {
      val openHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(v3DbFile.absolutePath)
          .callback(object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lender` TEXT NOT NULL, `category` TEXT NOT NULL, `originalPrincipalCents` INTEGER, `amountReceivedCents` INTEGER, `interestCents` INTEGER, `serviceFeeCents` INTEGER, `processingFeeCents` INTEGER, `totalScheduledRepaymentCents` INTEGER, `priorPaidCents` INTEGER NOT NULL, `isPriorPaidKnown` INTEGER NOT NULL, `trackedPaidCents` INTEGER NOT NULL, `remainingBalanceCents` INTEGER NOT NULL, `installmentAmountCents` INTEGER NOT NULL, `remainingInstallmentsCount` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `customIntervalDays` INTEGER, `lastKnownPaidDate` TEXT, `nextDueDate` TEXT, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `productClassification` TEXT NOT NULL DEFAULT 'UNKNOWN', `applicationProvider` TEXT, `actualCreditor` TEXT, `productName` TEXT, `loanReferenceId` TEXT, `loanBookingDateIso` TEXT, `disbursedAmountCents` INTEGER, `otherChargesCents` INTEGER, `outstandingPrincipalCents` INTEGER, `remainingInstallmentTotalCents` INTEGER, `amountCurrentlyDueCents` INTEGER, `pastDueAmountCents` INTEGER, `unpaidChargesCents` INTEGER, `creditLimitCents` INTEGER, `availableCreditCents` INTEGER, `usedCreditCents` INTEGER, `statementBalanceCents` INTEGER, `minimumDueCents` INTEGER, `statementDateIso` TEXT, `statementPeriod` TEXT, `originalInstallmentsCount` INTEGER, `paidInstallmentsCount` INTEGER, `parentDebtId` INTEGER, `obligationRole` TEXT NOT NULL DEFAULT 'STANDALONE', `scheduleVersion` INTEGER NOT NULL DEFAULT 1, `scheduleEffectiveDateIso` TEXT)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `installmentNumber` INTEGER NOT NULL, `dueDate` TEXT, `amountDueCents` INTEGER NOT NULL, `paidAmountCents` INTEGER NOT NULL, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `paidDate` TEXT, `linkedTransactionId` INTEGER, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, `scheduleVersion` INTEGER NOT NULL DEFAULT 1, `rawStatusLabel` TEXT, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_debtId` ON `installments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `displayLabel` TEXT NOT NULL, `screenshotIndex` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_attachments_debtId` ON `debt_attachments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `date` TEXT NOT NULL, `debtId` INTEGER, `installmentId` INTEGER, `reserveId` INTEGER, `expenseId` INTEGER, `allowanceId` INTEGER, `fundedFromReserveCents` INTEGER NOT NULL, `category` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `isCorrected` INTEGER NOT NULL, `isVoided` INTEGER NOT NULL, `correctionReason` TEXT, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_debtId` ON `transactions` (`debtId`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_installmentId` ON `transactions` (`installmentId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `allowance_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `receivedDate` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `sourceLabel` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_allowance_records_weekStartDate` ON `allowance_records` (`weekStartDate`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `category` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `isSchoolDay` INTEGER NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_reserves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `targetDebtId` INTEGER, `reserveType` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `appliedAmountCents` INTEGER NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_weekStartDate` ON `debt_reserves` (`weekStartDate`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_targetDebtId` ON `debt_reserves` (`targetDebtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`id` INTEGER NOT NULL, `minWeeklyAllowanceCents` INTEGER NOT NULL, `maxWeeklyAllowanceCents` INTEGER NOT NULL, `conservativeAllowanceCents` INTEGER NOT NULL, `schoolDaysPerWeek` INTEGER NOT NULL, `foodPerSchoolDayCents` INTEGER NOT NULL, `weeklyFoodBudgetCents` INTEGER NOT NULL, `weeklyOtherExpensesBudgetCents` INTEGER NOT NULL, `weeklyEmergencySavingsTargetCents` INTEGER NOT NULL, `weeklyDebtReserveTargetCents` INTEGER NOT NULL, `remindersEnabled` INTEGER NOT NULL, `allowanceReminderDayOfWeek` INTEGER NOT NULL, `reminderHour` INTEGER NOT NULL, `reminderMinute` INTEGER NOT NULL, `isManuallyEntered` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
          })
          .build()
      )
      openHelper.setWriteAheadLoggingEnabled(false)

      openHelper.writableDatabase.use { raw ->
        raw.execSQL(
          "INSERT INTO `debts` (`id`, `name`, `lender`, `category`, `priorPaidCents`, `isPriorPaidKnown`, `trackedPaidCents`, `remainingBalanceCents`, `installmentAmountCents`, `remainingInstallmentsCount`, `frequency`, `dateStatus`, `status`, `isManuallyEntered`, `notes`, `createdAtEpochMillis`, `updatedAtEpochMillis`, `scheduleVersion`) " +
            "VALUES (21, 'GLoan Original v1', 'GCash (Cash Loan)', 'Cash Loan', 0, 0, 0, 108738, 5178, 21, 'WEEKLY', 'CONFIRMED', 'ACTIVE', 1, '', 1000, 1000, 1)"
        )
        raw.execSQL(
          "INSERT INTO `installments` (`id`, `debtId`, `installmentNumber`, `dueDate`, `amountDueCents`, `paidAmountCents`, `dateStatus`, `status`, `isManuallyEntered`, `notes`, `scheduleVersion`) " +
            "VALUES (201, 21, 1, '2026-11-23', 5178, 0, 'CONFIRMED', 'UPCOMING', 1, 'v1 row', 1)"
        )
      }
      openHelper.close()

      val migratedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, v3DbFile.absolutePath)
        .addMigrations(
          BaonPlanDatabase.MIGRATION_1_2,
          BaonPlanDatabase.MIGRATION_2_3,
          BaonPlanDatabase.MIGRATION_3_4,
          BaonPlanDatabase.MIGRATION_4_5
        )
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries()
        .build()
      try {
        val migratedDebt = migratedDb.debtDao().getById(21L)!!
        assertEquals("GLoan Original v1", migratedDebt.name)
        assertNull(migratedDebt.lendingCompany)
        assertEquals(
          com.baonplan.finance.domain.model.ScheduleCompletenessStatus.NEEDS_USER_REVIEW,
          migratedDebt.scheduleCompletenessStatus
        )

        val migratedRepo = BaonPlanRepository(migratedDb, prefs)
        val restructuredDebt = migratedDebt.copy(
          lendingCompany = "Fuse Lending Inc.",
          contractualMaturityDateIso = "2027-04-12",
          projectedPayoffDateIso = "2027-06-21",
          scheduleVersion = 2,
          scheduleEffectiveDateIso = "2026-12-01",
          scheduleCompletenessStatus = com.baonplan.finance.domain.model.ScheduleCompletenessStatus.PARTIAL_SCHEDULE,
          projectionReliability = com.baonplan.finance.domain.model.ProjectionReliability.ESTIMATED_FROM_PARTIAL
        )
        val v2Rows = listOf(
          InstallmentEntity(
            debtId = 21L,
            installmentNumber = 1,
            dueDate = "2026-12-07",
            amountDueCents = 4_000L,
            dateStatus = DueDateStatus.CONFIRMED,
            status = InstallmentStatus.UPCOMING,
            scheduleVersion = 2,
            principalComponentCents = 3_500L,
            interestComponentCents = 400L,
            serviceFeeComponentCents = 100L,
            evidenceSourceLabel = "Restructuring Notice PDF p.1"
          )
        )
        migratedRepo.updateExistingDebtWithImportedSchedule(
          existingDebtId = 21L,
          updatedDebt = restructuredDebt,
          newScheduleRows = v2Rows
        )

        val updated = migratedDb.debtDao().getById(21L)!!
        assertEquals("Fuse Lending Inc.", updated.lendingCompany)
        assertEquals("2027-04-12", updated.contractualMaturityDateIso)
        assertEquals("2027-06-21", updated.projectedPayoffDateIso)
        assertEquals(2, updated.scheduleVersion)

        val allRows = migratedDb.installmentDao().getByDebtId(21L)
        assertEquals(2, allRows.size)
        assertEquals(1, allRows.count { it.scheduleVersion == 1 })
        val v2Stored = allRows.single { it.scheduleVersion == 2 }
        assertEquals(3_500L, v2Stored.principalComponentCents)
        assertEquals(400L, v2Stored.interestComponentCents)
        assertEquals(100L, v2Stored.serviceFeeComponentCents)
        assertEquals("Restructuring Notice PDF p.1", v2Stored.evidenceSourceLabel)
      } finally {
        migratedDb.close()
      }
    } finally {
      v3DbFile.delete()
    }
  }

  @Test
  fun migration4To5_preservesV4RecordsAndEnablesBudgetTablesAndLegacyCompatibility() = runTest {
    val v4DbFile = File(context.cacheDir, "migration_v4_${System.nanoTime()}.db")
    try {
      val openHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(v4DbFile.absolutePath)
          .callback(object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
              db.execSQL("CREATE TABLE IF NOT EXISTS `debts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lender` TEXT NOT NULL, `category` TEXT NOT NULL, `originalPrincipalCents` INTEGER, `amountReceivedCents` INTEGER, `interestCents` INTEGER, `serviceFeeCents` INTEGER, `processingFeeCents` INTEGER, `totalScheduledRepaymentCents` INTEGER, `priorPaidCents` INTEGER NOT NULL, `isPriorPaidKnown` INTEGER NOT NULL, `trackedPaidCents` INTEGER NOT NULL, `remainingBalanceCents` INTEGER NOT NULL, `installmentAmountCents` INTEGER NOT NULL, `remainingInstallmentsCount` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `customIntervalDays` INTEGER, `lastKnownPaidDate` TEXT, `nextDueDate` TEXT, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `productClassification` TEXT NOT NULL DEFAULT 'UNKNOWN', `applicationProvider` TEXT, `actualCreditor` TEXT, `productName` TEXT, `loanReferenceId` TEXT, `loanBookingDateIso` TEXT, `disbursedAmountCents` INTEGER, `otherChargesCents` INTEGER, `outstandingPrincipalCents` INTEGER, `remainingInstallmentTotalCents` INTEGER, `amountCurrentlyDueCents` INTEGER, `pastDueAmountCents` INTEGER, `unpaidChargesCents` INTEGER, `creditLimitCents` INTEGER, `availableCreditCents` INTEGER, `usedCreditCents` INTEGER, `statementBalanceCents` INTEGER, `minimumDueCents` INTEGER, `statementDateIso` TEXT, `statementPeriod` TEXT, `originalInstallmentsCount` INTEGER, `paidInstallmentsCount` INTEGER, `parentDebtId` INTEGER, `obligationRole` TEXT NOT NULL DEFAULT 'STANDALONE', `scheduleVersion` INTEGER NOT NULL DEFAULT 1, `scheduleEffectiveDateIso` TEXT, `lendingCompany` TEXT, `contractualMaturityDateIso` TEXT, `projectedPayoffDateIso` TEXT, `actualFullyPaidDateIso` TEXT, `scheduleCompletenessStatus` TEXT NOT NULL DEFAULT 'NEEDS_USER_REVIEW', `projectionReliability` TEXT NOT NULL DEFAULT 'INSUFFICIENT_DATA', `loanAnalysisJson` TEXT)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `installmentNumber` INTEGER NOT NULL, `dueDate` TEXT, `amountDueCents` INTEGER NOT NULL, `paidAmountCents` INTEGER NOT NULL, `dateStatus` TEXT NOT NULL, `status` TEXT NOT NULL, `paidDate` TEXT, `linkedTransactionId` INTEGER, `isManuallyEntered` INTEGER NOT NULL, `notes` TEXT NOT NULL, `scheduleVersion` INTEGER NOT NULL DEFAULT 1, `rawStatusLabel` TEXT, `principalComponentCents` INTEGER, `interestComponentCents` INTEGER, `serviceFeeComponentCents` INTEGER, `remainingPrincipalAfterCents` INTEGER, `evidenceSourceLabel` TEXT, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_debtId` ON `installments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `displayLabel` TEXT NOT NULL, `screenshotIndex` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_attachments_debtId` ON `debt_attachments` (`debtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `date` TEXT NOT NULL, `debtId` INTEGER, `installmentId` INTEGER, `reserveId` INTEGER, `expenseId` INTEGER, `allowanceId` INTEGER, `fundedFromReserveCents` INTEGER NOT NULL, `category` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `isCorrected` INTEGER NOT NULL, `isVoided` INTEGER NOT NULL, `correctionReason` TEXT, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_date` ON `transactions` (`date`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_debtId` ON `transactions` (`debtId`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_installmentId` ON `transactions` (`installmentId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `allowance_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `receivedDate` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `sourceLabel` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_allowance_records_weekStartDate` ON `allowance_records` (`weekStartDate`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `category` TEXT NOT NULL, `title` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `isSchoolDay` INTEGER NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `debt_reserves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `weekStartDate` TEXT NOT NULL, `targetDebtId` INTEGER, `reserveType` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `appliedAmountCents` INTEGER NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `isManuallyEntered` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_weekStartDate` ON `debt_reserves` (`weekStartDate`)")
              db.execSQL("CREATE INDEX IF NOT EXISTS `index_debt_reserves_targetDebtId` ON `debt_reserves` (`targetDebtId`)")
              db.execSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`id` INTEGER NOT NULL, `minWeeklyAllowanceCents` INTEGER NOT NULL, `maxWeeklyAllowanceCents` INTEGER NOT NULL, `conservativeAllowanceCents` INTEGER NOT NULL, `schoolDaysPerWeek` INTEGER NOT NULL, `foodPerSchoolDayCents` INTEGER NOT NULL, `weeklyFoodBudgetCents` INTEGER NOT NULL, `weeklyOtherExpensesBudgetCents` INTEGER NOT NULL, `weeklyEmergencySavingsTargetCents` INTEGER NOT NULL, `weeklyDebtReserveTargetCents` INTEGER NOT NULL, `remindersEnabled` INTEGER NOT NULL, `allowanceReminderDayOfWeek` INTEGER NOT NULL, `reminderHour` INTEGER NOT NULL, `reminderMinute` INTEGER NOT NULL, `isManuallyEntered` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
          })
          .build()
      )
      openHelper.setWriteAheadLoggingEnabled(false)

      openHelper.writableDatabase.use { raw ->
        raw.execSQL(
          "INSERT INTO `debts` (`id`, `name`, `lender`, `category`, `priorPaidCents`, `isPriorPaidKnown`, `trackedPaidCents`, `remainingBalanceCents`, `installmentAmountCents`, `remainingInstallmentsCount`, `frequency`, `nextDueDate`, `dateStatus`, `status`, `isManuallyEntered`, `notes`, `createdAtEpochMillis`, `updatedAtEpochMillis`) " +
            "VALUES (31, 'Legacy v4 Loan', 'BillEase', 'Cash Loan', 0, 0, 0, 100000, 50000, 2, 'MONTHLY', '2026-11-16', 'CONFIRMED', 'ACTIVE', 1, '', 1000, 1000)"
        )
        raw.execSQL(
          "INSERT INTO `allowance_records` (`id`, `weekStartDate`, `receivedDate`, `amountCents`, `sourceLabel`, `notes`, `isManuallyEntered`, `createdAtEpochMillis`) " +
            "VALUES (41, '2026-11-09', '2026-11-10', 200000, 'Weekly Baon', 'v4 cash', 1, 1000)"
        )
        raw.execSQL(
          "INSERT INTO `expenses` (`id`, `date`, `category`, `title`, `amountCents`, `isSchoolDay`, `notes`, `isManuallyEntered`, `createdAtEpochMillis`) " +
            "VALUES (51, '2026-11-11', 'FOOD', 'Lunch', 15000, 1, '', 1, 1000)"
        )
      }
      openHelper.close()

      val migratedDb = Room.databaseBuilder(context, BaonPlanDatabase::class.java, v4DbFile.absolutePath)
        .addMigrations(
          BaonPlanDatabase.MIGRATION_1_2,
          BaonPlanDatabase.MIGRATION_2_3,
          BaonPlanDatabase.MIGRATION_3_4,
          BaonPlanDatabase.MIGRATION_4_5
        )
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries()
        .build()
      try {
        val allowances = migratedDb.allowanceRecordDao().getAll()
        assertEquals(1, allowances.size)
        assertEquals(200_000L, allowances.single().amountCents)
        assertNull(allowances.single().incomeOccurrenceId)
        assertEquals(1, migratedDb.debtDao().getAll().size)
        assertEquals(1, migratedDb.expenseDao().getAll().size)

        val migratedRepo = BaonPlanRepository(migratedDb, prefs)
        val sourceId = migratedRepo.saveIncomeSource(
          IncomeSourceEntity(
            name = "Weekly Baon",
            frequency = IncomeFrequency.WEEKLY,
            expectedAmountCents = 200_000L,
            startDateIso = "2026-11-16",
            isPrimary = true
          )
        )
        assertTrue(sourceId > 0L)
        migratedRepo.upsertBudgetAllocation(BudgetCategoryKey.FOOD, 150_000L, enabled = true)

        val profile = migratedDb.budgetProfileDao().getProfile()
        assertNotNull(profile)
        assertTrue(profile!!.isConfigured)
        assertEquals(1, migratedDb.budgetAllocationDao().getAll().size)
      } finally {
        migratedDb.close()
      }
    } finally {
      v4DbFile.delete()
    }
  }

  @Test
  fun setupBudgetAndOccurrenceLifecycle_maintainsOneToOneLedgerSyncAndAllowsEditOrMissed() = runTest {
    repo.ensureInitialized()

    val sourceId = repo.setupBudgetWithPrimaryIncome(
      source = IncomeSourceEntity(
        name = "Main Allowance",
        frequency = IncomeFrequency.WEEKLY,
        expectedAmountCents = 200_000L,
        startDateIso = "2026-11-10",
        isPrimary = true
      ),
      profile = BudgetProfileEntity(
        isConfigured = true,
        rolloverEnabled = true
      ),
      markFirstOccurrenceReceivedToday = true
    )

    val occAfterSetup = repo.dbOccurrencesSnapshot()
    assertEquals(1, occAfterSetup.size)
    val occId = occAfterSetup.single().id
    assertEquals(IncomeOccurrenceStatus.RECEIVED, occAfterSetup.single().status)
    assertEquals(200_000L, occAfterSetup.single().actualReceivedAmountCents)
    assertEquals(1, db.allowanceRecordDao().getAll().size)
    assertEquals(1, db.transactionDao().getAll().count { !it.isVoided && it.type == TransactionType.ALLOWANCE })

    // Partial update: must update the SAME occurrence, AllowanceRecord, and Transaction (zero duplicates!)
    repo.confirmOrUpdateIncomeOccurrence(
      occurrenceId = occId,
      sourceId = sourceId,
      expectedDateIso = "2026-11-10",
      expectedAmountCents = 200_000L,
      status = IncomeOccurrenceStatus.PARTIALLY_RECEIVED,
      actualAmountCents = 120_000L,
      actualDateIso = "2026-11-10",
      notes = "Received partial"
    )
    val occAfterPartial = db.incomeOccurrenceDao().getById(occId)!!
    assertEquals(IncomeOccurrenceStatus.PARTIALLY_RECEIVED, occAfterPartial.status)
    assertEquals(120_000L, occAfterPartial.actualReceivedAmountCents)
    assertEquals(1, db.allowanceRecordDao().getAll().size)
    assertEquals(120_000L, db.allowanceRecordDao().getAll().single().amountCents)
    val activeAllowanceTxs = db.transactionDao().getAll().filter { !it.isVoided && it.type == TransactionType.ALLOWANCE }
    assertEquals(1, activeAllowanceTxs.size)
    assertEquals(120_000L, activeAllowanceTxs.single().amountCents)

    // Marking missed removes AllowanceRecord and voids linked Transaction cleanly
    repo.confirmOrUpdateIncomeOccurrence(
      occurrenceId = occId,
      sourceId = sourceId,
      expectedDateIso = "2026-11-10",
      expectedAmountCents = 200_000L,
      status = IncomeOccurrenceStatus.MISSED,
      actualAmountCents = 0L,
      actualDateIso = null,
      notes = "Delayed"
    )
    assertEquals(IncomeOccurrenceStatus.MISSED, db.incomeOccurrenceDao().getById(occId)!!.status)
    assertTrue(db.allowanceRecordDao().getAll().isEmpty())
    assertTrue(db.transactionDao().getAll().none { !it.isVoided && it.type == TransactionType.ALLOWANCE })

    // Re-confirm receipt then delete via deleteAllowance -> occurrence resets cleanly to EXPECTED
    repo.confirmOrUpdateIncomeOccurrence(
      occurrenceId = occId,
      sourceId = sourceId,
      expectedDateIso = "2026-11-10",
      expectedAmountCents = 200_000L,
      status = IncomeOccurrenceStatus.RECEIVED,
      actualAmountCents = 200_000L,
      actualDateIso = "2026-11-10"
    )
    val linkedAllowanceId = db.allowanceRecordDao().getAll().single().id
    repo.deleteAllowance(linkedAllowanceId)
    val resetOcc = db.incomeOccurrenceDao().getById(occId)!!
    assertEquals(IncomeOccurrenceStatus.EXPECTED, resetOcc.status)
    assertNull(resetOcc.linkedAllowanceRecordId)
    assertEquals(0L, resetOcc.actualReceivedAmountCents)
  }

  private suspend fun BaonPlanRepository.dbOccurrencesSnapshot() = db.incomeOccurrenceDao().getAll()

  @Test
  fun editIncomeSourceWithEffectiveDate_freezesPriorHistoryAndRecomputesFutureOnly() = runTest {
    repo.ensureInitialized()

    val sourceId = repo.saveIncomeSource(
      IncomeSourceEntity(
        name = "Weekly Baon",
        frequency = IncomeFrequency.WEEKLY,
        expectedAmountCents = 200_000L,
        startDateIso = "2026-11-02",
        isPrimary = true
      )
    )
    val initialSource = db.incomeSourceDao().getById(sourceId)!!

    repo.saveIncomeSource(
      source = initialSource.copy(expectedAmountCents = 250_000L),
      effectiveFromDateIso = "2026-11-16"
    )

    val sources = db.incomeSourceDao().getAll()
    val occurrences = db.incomeOccurrenceDao().getAll()
    val period1Window = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 11, 2),
      profile = db.budgetProfileDao().getProfile(),
      sources = sources,
      periodOffset = 0
    )
    val period1Resolved = BudgetRecurrenceEngine.resolveOccurrencesForWindow(sources, occurrences, period1Window, sourceId)
    assertEquals(1, period1Resolved.size)
    assertEquals(200_000L, period1Resolved.single().expectedAmountCents)

    val period3Window = BudgetRecurrenceEngine.resolvePeriodWindow(
      today = LocalDate.of(2026, 11, 16),
      profile = db.budgetProfileDao().getProfile(),
      sources = sources,
      periodOffset = 0
    )
    val period3Resolved = BudgetRecurrenceEngine.resolveOccurrencesForWindow(sources, occurrences, period3Window, sourceId)
    assertEquals(1, period3Resolved.size)
    assertEquals(250_000L, period3Resolved.single().expectedAmountCents)
  }

  @Test
  fun scheduleVersionIsolationAndBackupV5RoundTrip_preservesRestructuredScheduleAndBudget() = runTest {
    repo.ensureInitialized()

    val sourceId = repo.saveIncomeSource(
      IncomeSourceEntity(
        name = "Monthly Scholarship",
        frequency = IncomeFrequency.MONTHLY,
        expectedAmountCents = 800_000L,
        startDateIso = "2026-11-01",
        isPrimary = true
      )
    )
    repo.saveBudgetProfile(
      BudgetProfileEntity(
        isConfigured = true,
        monthlyCycleMode = MonthlyCycleMode.CALENDAR_MONTH,
        rolloverEnabled = true,
        reserveAheadEnabled = true,
        primarySourceId = sourceId
      )
    )
    repo.upsertBudgetAllocation(BudgetCategoryKey.FOOD, 200_000L, enabled = true)

    val debtId = repo.saveImportedDebtWithSchedule(
      debt = DebtEntity(
        name = "GLoan Original",
        lender = "GCash",
        category = "Cash Loan",
        productClassification = LoanProductClassification.FIXED_INSTALLMENT_LOAN,
        remainingBalanceCents = 99_900L,
        installmentAmountCents = 99_900L,
        remainingInstallmentsCount = 1,
        frequency = RepaymentFrequency.MONTHLY,
        nextDueDate = "2026-11-12",
        scheduleVersion = 1
      ),
      scheduleRows = listOf(
        InstallmentEntity(
          debtId = 0L,
          installmentNumber = 1,
          dueDate = "2026-11-12",
          amountDueCents = 99_900L,
          dateStatus = DueDateStatus.CONFIRMED,
          status = InstallmentStatus.UPCOMING,
          scheduleVersion = 1
        )
      )
    )
    val savedV1 = db.debtDao().getById(debtId)!!
    repo.updateExistingDebtWithImportedSchedule(
      existingDebtId = debtId,
      updatedDebt = savedV1.copy(
        name = "GLoan Restructured",
        remainingBalanceCents = 90_000L,
        installmentAmountCents = 45_000L,
        remainingInstallmentsCount = 2,
        nextDueDate = "2026-11-16",
        scheduleVersion = 2
      ),
      newScheduleRows = listOf(
        InstallmentEntity(
          debtId = debtId,
          installmentNumber = 1,
          dueDate = "2026-11-16",
          amountDueCents = 45_000L,
          dateStatus = DueDateStatus.CONFIRMED,
          status = InstallmentStatus.UPCOMING,
          scheduleVersion = 2
        ),
        InstallmentEntity(
          debtId = debtId,
          installmentNumber = 2,
          dueDate = "2026-12-16",
          amountDueCents = 45_000L,
          dateStatus = DueDateStatus.CONFIRMED,
          status = InstallmentStatus.UPCOMING,
          scheduleVersion = 2
        )
      )
    )

    val debts = db.debtDao().getAll()
    val installments = db.installmentDao().getAll()
    val overview = CashFlowBudgetEngine.calculateOverview(
      today = LocalDate.of(2026, 11, 10),
      profile = db.budgetProfileDao().getProfile(),
      sources = db.incomeSourceDao().getAll(),
      occurrences = db.incomeOccurrenceDao().getAll(),
      allocations = db.budgetAllocationDao().getAll(),
      allowances = db.allowanceRecordDao().getAll(),
      expenses = db.expenseDao().getAll(),
      reserves = db.debtReserveDao().getAll(),
      transactions = db.transactionDao().getAll(),
      debts = debts,
      installments = installments
    )
    assertEquals(45_000L, overview.periodDebtTotalCents)
    assertEquals(1, overview.periodDebtItems.size)

    val grouping = PaymentGroupingEngine.buildGroupedPayments(
      debts = debts,
      installments = installments,
      today = LocalDate.of(2026, 11, 10)
    )
    assertEquals(45_000L, grouping.nextGroup!!.knownTotalCents)
    assertNull(grouping.overdueGroup)

    // Full v5 backup export -> wipe -> restore round trip
    val json = BackupSerializer.encodeBackupJson(repo.createBackupPayload())
    repo.clearAllData()
    assertTrue(db.incomeSourceDao().getAll().isEmpty())

    repo.restoreFromBackup(BackupSerializer.decodeBackupJson(json).getOrThrow())
    assertEquals(1, db.incomeSourceDao().getAll().size)
    assertEquals("Monthly Scholarship", db.incomeSourceDao().getAll().single().name)
    assertTrue(db.budgetProfileDao().getProfile()!!.isConfigured)
    assertTrue(db.budgetProfileDao().getProfile()!!.reserveAheadEnabled)
    assertEquals(1, db.budgetAllocationDao().getAll().size)
    assertEquals(200_000L, db.budgetAllocationDao().getAll().single().allocatedAmountCents)
  }
}
