package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.ExpenseCategory
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "expenses",
  indices = [Index(value = ["date"])]
)
data class ExpenseEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val date: String,
  val category: ExpenseCategory,
  val title: String,
  val amountCents: Long,
  val isSchoolDay: Boolean = false,
  val notes: String = "",
  val isManuallyEntered: Boolean = true,
  val createdAtEpochMillis: Long = System.currentTimeMillis()
)
