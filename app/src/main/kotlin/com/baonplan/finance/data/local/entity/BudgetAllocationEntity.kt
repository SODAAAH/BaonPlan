package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.BudgetCategoryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "budget_allocations",
  indices = [Index(value = ["categoryKey"], unique = true)]
)
data class BudgetAllocationEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val categoryKey: BudgetCategoryKey,
  val allocatedAmountCents: Long,
  val isEnabled: Boolean = true,
  val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
