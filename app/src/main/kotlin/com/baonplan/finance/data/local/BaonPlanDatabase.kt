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
  version = 2,
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

    @Volatile
    private var INSTANCE: BaonPlanDatabase? = null

    fun getInstance(context: Context): BaonPlanDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          BaonPlanDatabase::class.java,
          DATABASE_NAME
        )
          .addMigrations(MIGRATION_1_2)
          .build()
          .also { INSTANCE = it }
      }
    }
  }
}
