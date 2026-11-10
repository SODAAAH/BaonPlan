package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetProfileDao {
  @Query("SELECT * FROM budget_profiles WHERE id = 1")
  fun observeProfile(): Flow<BudgetProfileEntity?>

  @Query("SELECT * FROM budget_profiles WHERE id = 1")
  suspend fun getProfile(): BudgetProfileEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(profile: BudgetProfileEntity)

  @Query("DELETE FROM budget_profiles")
  suspend fun deleteAll()
}
