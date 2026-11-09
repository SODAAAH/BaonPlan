package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.TransactionType
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "transactions",
  indices = [
    Index(value = ["date"]),
    Index(value = ["debtId"]),
    Index(value = ["installmentId"])
  ]
)
data class TransactionEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val type: TransactionType,
  val title: String,
  val amountCents: Long,
  val date: String,
  val debtId: Long? = null,
  val installmentId: Long? = null,
  val reserveId: Long? = null,
  val expenseId: Long? = null,
  val allowanceId: Long? = null,
  val fundedFromReserveCents: Long = 0L,
  val category: String = "",
  val notes: String = "",
  val isManuallyEntered: Boolean = true,
  val isCorrected: Boolean = false,
  val isVoided: Boolean = false,
  val correctionReason: String? = null,
  val createdAtEpochMillis: Long = System.currentTimeMillis()
)
