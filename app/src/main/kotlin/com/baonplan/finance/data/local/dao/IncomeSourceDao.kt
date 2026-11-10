package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomeSourceDao {
  @Query("SELECT * FROM income_sources ORDER BY isPrimary DESC, isActive DESC, startDateIso ASC, id ASC")
  fun observeAll(): Flow<List<IncomeSourceEntity>>

  @Query("SELECT * FROM income_sources ORDER BY isPrimary DESC, isActive DESC, startDateIso ASC, id ASC")
  suspend fun getAll(): List<IncomeSourceEntity>

  @Query("SELECT * FROM income_sources WHERE id = :id")
  suspend fun getById(id: Long): IncomeSourceEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(source: IncomeSourceEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(sources: List<IncomeSourceEntity>): List<Long>

  @Update
  suspend fun update(source: IncomeSourceEntity)

  @Query("DELETE FROM income_sources WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM income_sources")
  suspend fun deleteAll()
}
