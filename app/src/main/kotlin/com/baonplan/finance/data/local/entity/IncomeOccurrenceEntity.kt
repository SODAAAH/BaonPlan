package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "income_occurrences",
  foreignKeys = [
    ForeignKey(
      entity = IncomeSourceEntity::class,
      parentColumns = ["id"],
      childColumns = ["sourceId"],
      onDelete = ForeignKey.SET_NULL
    )
  ],
  indices = [
    Index(value = ["sourceId"]),
    Index(value = ["expectedDateIso"]),
    Index(value = ["actualReceivedDateIso"])
  ]
)
data class IncomeOccurrenceEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val sourceId: Long? = null,
  val sourceNameSnapshot: String = "",
  val expectedDateIso: String,
  val expectedAmountCents: Long,
  val actualReceivedAmountCents: Long = 0L,
  val actualReceivedDateIso: String? = null,
  val status: IncomeOccurrenceStatus = IncomeOccurrenceStatus.EXPECTED,
  val linkedAllowanceRecordId: Long? = null,
  val isFrozenScheduleHistory: Boolean = false,
  val notes: String = "",
  val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
