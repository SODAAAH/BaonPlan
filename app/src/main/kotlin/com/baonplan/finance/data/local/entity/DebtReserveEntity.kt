package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.ReserveStatus
import com.baonplan.finance.domain.model.ReserveType
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "debt_reserves",
  indices = [Index(value = ["weekStartDate"]), Index(value = ["targetDebtId"])]
)
data class DebtReserveEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val weekStartDate: String,
  val targetDebtId: Long? = null,
  val reserveType: ReserveType,
  val amountCents: Long,
  val appliedAmountCents: Long = 0L,
  val status: ReserveStatus = ReserveStatus.RESERVED,
  val notes: String = "",
  val isManuallyEntered: Boolean = true,
  val createdAtEpochMillis: Long = System.currentTimeMillis()
) {
  val remainingReservedCents: Long
    get() = if (status == ReserveStatus.RESERVED) {
      (amountCents - appliedAmountCents).coerceAtLeast(0L)
    } else {
      0L
    }
}
