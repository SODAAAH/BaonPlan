package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "debt_attachments",
  foreignKeys = [
    ForeignKey(
      entity = DebtEntity::class,
      parentColumns = ["id"],
      childColumns = ["debtId"],
      onDelete = ForeignKey.CASCADE
    )
  ],
  indices = [Index(value = ["debtId"])]
)
data class DebtAttachmentEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val debtId: Long,
  val fileName: String,
  val displayLabel: String,
  val screenshotIndex: Int = 0,
  val createdAtEpochMillis: Long = System.currentTimeMillis()
)
