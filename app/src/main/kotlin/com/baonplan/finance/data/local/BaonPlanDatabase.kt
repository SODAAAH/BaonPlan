package com.baonplan.finance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baonplan.finance.data.local.dao.AllowanceRecordDao
import com.baonplan.finance.data.local.dao.DebtAttachmentDao
import com.baonplan.finance.data.local.dao.DebtDao
import com.baonplan.finance.data.local.dao.DebtReserveDao
import com.baonplan.finance.data.local.dao.ExpenseDao
import com.baonplan.finance.data.local.dao.InstallmentDao
import com.baonplan.finance.data.local.dao.TransactionDao
import com.baonplan.finance.data.local.dao.UserSettingsDao
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import com.baonplan.finance.data.local.entity.ExpenseEntity
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
    DebtAttachmentEntity::class
  ],
  version = 4,
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

    @Volatile
    private var INSTANCE: BaonPlanDatabase? = null

    fun getInstance(context: Context): BaonPlanDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          BaonPlanDatabase::class.java,
          DATABASE_NAME
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
