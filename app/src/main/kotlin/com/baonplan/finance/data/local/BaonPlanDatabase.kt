package com.baonplan.finance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.baonplan.finance.data.local.dao.AllowanceRecordDao
import com.baonplan.finance.data.local.dao.DebtDao
import com.baonplan.finance.data.local.dao.DebtReserveDao
import com.baonplan.finance.data.local.dao.ExpenseDao
import com.baonplan.finance.data.local.dao.InstallmentDao
import com.baonplan.finance.data.local.dao.TransactionDao
import com.baonplan.finance.data.local.dao.UserSettingsDao
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
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
    UserSettingsEntity::class
  ],
  version = 1,
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

  companion object {
    const val DATABASE_NAME = "baonplan.db"

    @Volatile
    private var INSTANCE: BaonPlanDatabase? = null

    fun getInstance(context: Context): BaonPlanDatabase {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          BaonPlanDatabase::class.java,
          DATABASE_NAME
        ).build().also { INSTANCE = it }
      }
    }
  }
}
