package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.DebtEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {
  @Query("SELECT * FROM debts ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PAID' THEN 1 ELSE 2 END, remainingBalanceCents DESC, id ASC")
  fun observeAll(): Flow<List<DebtEntity>>

  @Query("SELECT * FROM debts ORDER BY id ASC")
  suspend fun getAll(): List<DebtEntity>

  @Query("SELECT * FROM debts WHERE id = :debtId")
  fun observeById(debtId: Long): Flow<DebtEntity?>

  @Query("SELECT * FROM debts WHERE id = :debtId")
  suspend fun getById(debtId: Long): DebtEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(debt: DebtEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(debts: List<DebtEntity>): List<Long>

  @Update
  suspend fun update(debt: DebtEntity)

  @Query("DELETE FROM debts WHERE id = :debtId")
  suspend fun deleteById(debtId: Long)

  @Query("DELETE FROM debts")
  suspend fun deleteAll()
}
