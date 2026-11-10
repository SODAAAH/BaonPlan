package com.baonplan.finance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baonplan.finance.data.local.dao.AllowanceRecordDao
import com.baonplan.finance.data.local.dao.BudgetAllocationDao
import com.baonplan.finance.data.local.dao.BudgetProfileDao
import com.baonplan.finance.data.local.dao.DebtAttachmentDao
import com.baonplan.finance.data.local.dao.DebtDao
import com.baonplan.finance.data.local.dao.DebtReserveDao
import com.baonplan.finance.data.local.dao.ExpenseDao
import com.baonplan.finance.data.local.dao.IncomeOccurrenceDao
import com.baonplan.finance.data.local.dao.IncomeSourceDao
import com.baonplan.finance.data.local.dao.InstallmentDao
import com.baonplan.finance.data.local.dao.TransactionDao
import com.baonplan.finance.data.local.dao.UserSettingsDao
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

@Database(
  entities = [
    DebtEntity::class,
    InstallmentEntity::class,
    TransactionEntity::class,
    AllowanceRecordEntity::class,
    ExpenseEntity::class,
    DebtReserveEntity::class,
    UserSettingsEntity::class,
    DebtAttachmentEntity::class,
    BudgetProfileEntity::class,
    IncomeSourceEntity::class,
    IncomeOccurrenceEntity::class,
    BudgetAllocationEntity::class
  ],
  version = 5,
  exportSchema = true
)
@TypeConverters(RoomConverters::class)
abstract class BaonPlanDatabase : RoomDatabase() {
  abstract fun debtDao(): DebtDao
  abstract fun installmentDao(): InstallmentDao
  abstract fun transactionDao(): TransactionDao
  abstract fun allowanceRecordDao(): AllowanceRecordDao
  abstract fun expenseDao(): ExpenseDao
  abstract fun debtReserveDao(): DebtReserveDao
  abstract fun userSettingsDao(): UserSettingsDao
  abstract fun debtAttachmentDao(): DebtAttachmentDao
  abstract fun budgetProfileDao(): BudgetProfileDao
  abstract fun incomeSourceDao(): IncomeSourceDao
  abstract fun incomeOccurrenceDao(): IncomeOccurrenceDao
  abstract fun budgetAllocationDao(): BudgetAllocationDao

  companion object {
    const val DATABASE_NAME = "baonplan.db"

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `debt_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `debtId` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `displayLabel` TEXT NOT NULL, `screenshotIndex` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`debtId`) REFERENCES `debts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_debt_attachments_debtId` ON `debt_attachments` (`debtId`)"
        )
      }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `productClassification` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `applicationProvider` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `actualCreditor` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `productName` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `loanReferenceId` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `loanBookingDateIso` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `disbursedAmountCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `otherChargesCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `outstandingPrincipalCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `remainingInstallmentTotalCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `amountCurrentlyDueCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `pastDueAmountCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `unpaidChargesCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `creditLimitCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `availableCreditCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `usedCreditCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `statementBalanceCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `minimumDueCents` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `statementDateIso` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `statementPeriod` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `originalInstallmentsCount` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `paidInstallmentsCount` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `parentDebtId` INTEGER")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `obligationRole` TEXT NOT NULL DEFAULT 'STANDALONE'")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `scheduleVersion` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `scheduleEffectiveDateIso` TEXT")

        db.execSQL("ALTER TABLE `installments` ADD COLUMN `scheduleVersion` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `installments` ADD COLUMN `rawStatusLabel` TEXT")
      }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `lendingCompany` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `contractualMaturityDateIso` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `projectedPayoffDateIso` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `actualFullyPaidDateIso` TEXT")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `scheduleCompletenessStatus` TEXT NOT NULL DEFAULT 'NEEDS_USER_REVIEW'")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `projectionReliability` TEXT NOT NULL DEFAULT 'INSUFFICIENT_DATA'")
        db.execSQL("ALTER TABLE `debts` ADD COLUMN `loanAnalysisJson` TEXT")

        db.execSQL("ALTER TABLE `installments` ADD COLUMN `principalComponentCents` INTEGER")
        db.execSQL("ALTER TABLE `installments` ADD COLUMN `interestComponentCents` INTEGER")
        db.execSQL("ALTER TABLE `installments` ADD COLUMN `serviceFeeComponentCents` INTEGER")
        db.execSQL("ALTER TABLE `installments` ADD COLUMN `remainingPrincipalAfterCents` INTEGER")
        db.execSQL("ALTER TABLE `installments` ADD COLUMN `evidenceSourceLabel` TEXT")
      }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `allowance_records` ADD COLUMN `incomeOccurrenceId` INTEGER")

        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `budget_profiles` (`id` INTEGER NOT NULL, `isConfigured` INTEGER NOT NULL, `periodStyle` TEXT NOT NULL, `customPeriodDays` INTEGER, `weeklyCycleMode` TEXT NOT NULL, `monthlyCycleMode` TEXT NOT NULL, `rolloverEnabled` INTEGER NOT NULL, `reserveAheadEnabled` INTEGER NOT NULL, `includeExpectedIncomeInProjection` INTEGER NOT NULL, `includeOverdueInBudget` INTEGER NOT NULL, `debtDeductionMode` TEXT NOT NULL, `primarySourceId` INTEGER, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )

        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `income_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `frequency` TEXT NOT NULL, `expectedAmountCents` INTEGER NOT NULL, `isVariableAmount` INTEGER NOT NULL, `startDateIso` TEXT NOT NULL, `endDateIso` TEXT, `customIntervalDays` INTEGER, `customIntervalMonths` INTEGER, `twiceMonthlyPattern` TEXT NOT NULL, `twiceMonthlyDay1` INTEGER, `twiceMonthlyDay2` INTEGER, `customDatesCsv` TEXT NOT NULL, `anchorDayOfMonth` INTEGER, `isActive` INTEGER NOT NULL, `isPaused` INTEGER NOT NULL, `pausedFromDateIso` TEXT, `effectiveFromDateIso` TEXT, `isPrimary` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)"
        )

        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `income_occurrences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER, `sourceNameSnapshot` TEXT NOT NULL, `expectedDateIso` TEXT NOT NULL, `expectedAmountCents` INTEGER NOT NULL, `actualReceivedAmountCents` INTEGER NOT NULL, `actualReceivedDateIso` TEXT, `status` TEXT NOT NULL, `linkedAllowanceRecordId` INTEGER, `isFrozenScheduleHistory` INTEGER NOT NULL, `notes` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, FOREIGN KEY(`sourceId`) REFERENCES `income_sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_income_occurrences_sourceId` ON `income_occurrences` (`sourceId`)"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_income_occurrences_expectedDateIso` ON `income_occurrences` (`expectedDateIso`)"
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_income_occurrences_actualReceivedDateIso` ON `income_occurrences` (`actualReceivedDateIso`)"
        )

        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `budget_allocations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `categoryKey` TEXT NOT NULL, `allocatedAmountCents` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL)"
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_allocations_categoryKey` ON `budget_allocations` (`categoryKey`)"
        )
      }
    }

    @Volatile
    private var INSTANCE: BaonPlanDatabase? = null

    fun getInstance(context: Context): BaonPlanDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          BaonPlanDatabase::class.java,
          DATABASE_NAME
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
