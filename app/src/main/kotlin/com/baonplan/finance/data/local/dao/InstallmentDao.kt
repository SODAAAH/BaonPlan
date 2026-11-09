package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.InstallmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallmentDao {
  @Query("SELECT * FROM installments ORDER BY CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, installmentNumber ASC, id ASC")
  fun observeAll(): Flow<List<InstallmentEntity>>

  @Query("SELECT * FROM installments ORDER BY debtId ASC, installmentNumber ASC")
  suspend fun getAll(): List<InstallmentEntity>

  @Query("SELECT * FROM installments WHERE debtId = :debtId ORDER BY installmentNumber ASC, CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END, dueDate ASC, id ASC")
  fun observeByDebtId(debtId: Long): Flow<List<InstallmentEntity>>

  @Query("SELECT * FROM installments WHERE debtId = :debtId ORDER BY installmentNumber ASC, id ASC")
  suspend fun getByDebtId(debtId: Long): List<InstallmentEntity>

  @Query("SELECT * FROM installments WHERE id = :installmentId")
  suspend fun getById(installmentId: Long): InstallmentEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(installment: InstallmentEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(installments: List<InstallmentEntity>): List<Long>

  @Update
  suspend fun update(installment: InstallmentEntity)

  @Query("DELETE FROM installments WHERE debtId = :debtId AND status != 'PAID'")
  suspend fun deleteUnpaidByDebtId(debtId: Long)

  @Query("DELETE FROM installments WHERE debtId = :debtId")
  suspend fun deleteByDebtId(debtId: Long)

  @Query("DELETE FROM installments")
  suspend fun deleteAll()
}
