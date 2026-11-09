package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.DebtReserveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtReserveDao {
  @Query("SELECT * FROM debt_reserves ORDER BY createdAtEpochMillis DESC, id DESC")
  fun observeAll(): Flow<List<DebtReserveEntity>>

  @Query("SELECT * FROM debt_reserves ORDER BY createdAtEpochMillis DESC, id DESC")
  suspend fun getAll(): List<DebtReserveEntity>

  @Query("SELECT * FROM debt_reserves WHERE id = :id")
  suspend fun getById(id: Long): DebtReserveEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(reserve: DebtReserveEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(reserves: List<DebtReserveEntity>): List<Long>

  @Update
  suspend fun update(reserve: DebtReserveEntity)

  @Query("DELETE FROM debt_reserves WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM debt_reserves")
  suspend fun deleteAll()
}
