package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.IncomeOccurrenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomeOccurrenceDao {
  @Query("SELECT * FROM income_occurrences ORDER BY expectedDateIso DESC, id DESC")
  fun observeAll(): Flow<List<IncomeOccurrenceEntity>>

  @Query("SELECT * FROM income_occurrences ORDER BY expectedDateIso DESC, id DESC")
  suspend fun getAll(): List<IncomeOccurrenceEntity>

  @Query("SELECT * FROM income_occurrences WHERE id = :id")
  suspend fun getById(id: Long): IncomeOccurrenceEntity?

  @Query("SELECT * FROM income_occurrences WHERE sourceId = :sourceId ORDER BY expectedDateIso ASC, id ASC")
  suspend fun getBySourceId(sourceId: Long): List<IncomeOccurrenceEntity>

  @Query("SELECT * FROM income_occurrences WHERE sourceId = :sourceId AND expectedDateIso = :expectedDateIso LIMIT 1")
  suspend fun findBySourceAndExpectedDate(sourceId: Long, expectedDateIso: String): IncomeOccurrenceEntity?

  @Query("SELECT * FROM income_occurrences WHERE linkedAllowanceRecordId = :allowanceId LIMIT 1")
  suspend fun findByLinkedAllowanceId(allowanceId: Long): IncomeOccurrenceEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(occurrence: IncomeOccurrenceEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(occurrences: List<IncomeOccurrenceEntity>): List<Long>

  @Update
  suspend fun update(occurrence: IncomeOccurrenceEntity)

  @Query("DELETE FROM income_occurrences WHERE sourceId = :sourceId AND status = 'EXPECTED' AND expectedDateIso >= :fromDateIso")
  suspend fun deleteFutureExpectedBySource(sourceId: Long, fromDateIso: String)

  @Query("DELETE FROM income_occurrences WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("DELETE FROM income_occurrences")
  suspend fun deleteAll()
}
