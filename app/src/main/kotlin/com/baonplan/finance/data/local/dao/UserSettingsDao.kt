package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baonplan.finance.data.local.entity.UserSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserSettingsDao {
  @Query("SELECT * FROM user_settings WHERE id = 1")
  fun observeSettings(): Flow<UserSettingsEntity?>

  @Query("SELECT * FROM user_settings WHERE id = 1")
  suspend fun getSettings(): UserSettingsEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(settings: UserSettingsEntity)

  @Query("DELETE FROM user_settings")
  suspend fun deleteAll()
}
