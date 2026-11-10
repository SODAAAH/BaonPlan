package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
  @Query("SELECT * FROM transactions ORDER BY date DESC, createdAtEpochMillis DESC, id DESC")
  fun observeAll(): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions ORDER BY date DESC, createdAtEpochMillis DESC, id DESC")
  suspend fun getAll(): List<TransactionEntity>

  @Query("SELECT * FROM transactions WHERE debtId = :debtId ORDER BY date DESC, createdAtEpochMillis DESC, id DESC")
  fun observeByDebtId(debtId: Long): Flow<List<TransactionEntity>>

  @Query("SELECT * FROM transactions WHERE id = :id")
  suspend fun getById(id: Long): TransactionEntity?

  @Query("SELECT * FROM transactions WHERE allowanceId = :allowanceId AND isVoided = 0 ORDER BY id DESC LIMIT 1")
  suspend fun findActiveByAllowanceId(allowanceId: Long): TransactionEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(transaction: TransactionEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

  @Update
  suspend fun update(transaction: TransactionEntity)

  @Query("DELETE FROM transactions WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM transactions")
  suspend fun deleteAll()
}
