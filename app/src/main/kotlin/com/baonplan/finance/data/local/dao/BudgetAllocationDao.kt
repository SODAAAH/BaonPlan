package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.baonplan.finance.data.local.entity.BudgetAllocationEntity
import com.baonplan.finance.domain.model.BudgetCategoryKey
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetAllocationDao {
  @Query("SELECT * FROM budget_allocations ORDER BY id ASC")
  fun observeAll(): Flow<List<BudgetAllocationEntity>>

  @Query("SELECT * FROM budget_allocations ORDER BY id ASC")
  suspend fun getAll(): List<BudgetAllocationEntity>

  @Query("SELECT * FROM budget_allocations WHERE categoryKey = :key LIMIT 1")
  suspend fun getByCategoryKey(key: BudgetCategoryKey): BudgetAllocationEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(allocation: BudgetAllocationEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(allocations: List<BudgetAllocationEntity>): List<Long>

  @Update
  suspend fun update(allocation: BudgetAllocationEntity)

  @Query("DELETE FROM budget_allocations WHERE categoryKey = :key")
  suspend fun deleteByCategoryKey(key: BudgetCategoryKey)

  @Query("DELETE FROM budget_allocations")
  suspend fun deleteAll()
}
