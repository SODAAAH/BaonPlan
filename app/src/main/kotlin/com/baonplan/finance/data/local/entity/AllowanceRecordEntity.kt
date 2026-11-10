package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "allowance_records",
  indices = [Index(value = ["weekStartDate"])]
)
data class AllowanceRecordEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val weekStartDate: String,
  val receivedDate: String,
  val amountCents: Long,
  val sourceLabel: String = "Weekly Allowance",
  val notes: String = "",
  val isManuallyEntered: Boolean = true,
  val incomeOccurrenceId: Long? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis()
)
