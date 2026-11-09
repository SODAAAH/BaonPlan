package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.ExpenseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
  @Query("SELECT * FROM expenses ORDER BY date DESC, createdAtEpochMillis DESC, id DESC")
  fun observeAll(): Flow<List<ExpenseEntity>>

  @Query("SELECT * FROM expenses ORDER BY date DESC, createdAtEpochMillis DESC, id DESC")
  suspend fun getAll(): List<ExpenseEntity>

  @Query("SELECT * FROM expenses WHERE id = :id")
  suspend fun getById(id: Long): ExpenseEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(expense: ExpenseEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(expenses: List<ExpenseEntity>): List<Long>

  @Update
  suspend fun update(expense: ExpenseEntity)

  @Query("DELETE FROM expenses WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM expenses")
  suspend fun deleteAll()
}
