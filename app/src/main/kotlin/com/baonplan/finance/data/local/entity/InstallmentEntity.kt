package com.baonplan.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import kotlinx.serialization.Serializable

@Serializable
@Entity(
  tableName = "installments",
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
data class InstallmentEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val debtId: Long,
  val installmentNumber: Int,
  val dueDate: String? = null,
  val amountDueCents: Long,
  val paidAmountCents: Long = 0L,
  val dateStatus: DueDateStatus = DueDateStatus.UNCONFIRMED,
  val status: InstallmentStatus = InstallmentStatus.UPCOMING,
  val paidDate: String? = null,
  val linkedTransactionId: Long? = null,
  val isManuallyEntered: Boolean = true,
  val notes: String = "",
  @ColumnInfo(defaultValue = "1")
  val scheduleVersion: Int = 1,
  val rawStatusLabel: String? = null
)
