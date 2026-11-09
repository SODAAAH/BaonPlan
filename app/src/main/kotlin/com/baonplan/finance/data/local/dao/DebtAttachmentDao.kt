package com.baonplan.finance.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtAttachmentDao {
  @Query("SELECT * FROM debt_attachments ORDER BY debtId ASC, screenshotIndex ASC, id ASC")
  fun observeAll(): Flow<List<DebtAttachmentEntity>>

  @Query("SELECT * FROM debt_attachments WHERE debtId = :debtId ORDER BY screenshotIndex ASC, id ASC")
  fun observeByDebtId(debtId: Long): Flow<List<DebtAttachmentEntity>>

  @Query("SELECT * FROM debt_attachments WHERE debtId = :debtId ORDER BY screenshotIndex ASC, id ASC")
  suspend fun getByDebtId(debtId: Long): List<DebtAttachmentEntity>

  @Query("SELECT * FROM debt_attachments ORDER BY debtId ASC, screenshotIndex ASC, id ASC")
  suspend fun getAll(): List<DebtAttachmentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(attachments: List<DebtAttachmentEntity>): List<Long>

  @Query("DELETE FROM debt_attachments WHERE debtId = :debtId")
  suspend fun deleteByDebtId(debtId: Long)

  @Query("DELETE FROM debt_attachments")
  suspend fun deleteAll()
}
