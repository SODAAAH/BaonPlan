package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.AllowanceRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AllowanceRecordDao {
  @Query("SELECT * FROM allowance_records ORDER BY receivedDate DESC, id DESC")
  fun observeAll(): Flow<List<AllowanceRecordEntity>>

  @Query("SELECT * FROM allowance_records ORDER BY receivedDate DESC, id DESC")
  suspend fun getAll(): List<AllowanceRecordEntity>

  @Query("SELECT * FROM allowance_records WHERE id = :id")
  suspend fun getById(id: Long): AllowanceRecordEntity?

  @Query("SELECT * FROM allowance_records WHERE incomeOccurrenceId = :occurrenceId LIMIT 1")
  suspend fun findByIncomeOccurrenceId(occurrenceId: Long): AllowanceRecordEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(record: AllowanceRecordEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(records: List<AllowanceRecordEntity>): List<Long>

  @Update
  suspend fun update(record: AllowanceRecordEntity)

  @Query("DELETE FROM allowance_records WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM allowance_records")
  suspend fun deleteAll()
}
