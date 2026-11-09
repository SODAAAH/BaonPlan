package com.baonplan.finance.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "debts")
data class DebtEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0L,
  val name: String,
  val lender: String,
  val category: String,
  val originalPrincipalCents: Long? = null,
  val amountReceivedCents: Long? = null,
  val interestCents: Long? = null,
  val serviceFeeCents: Long? = null,
  val processingFeeCents: Long? = null,
  val totalScheduledRepaymentCents: Long? = null,
  val priorPaidCents: Long = 0L,
  val isPriorPaidKnown: Boolean = false,
  val trackedPaidCents: Long = 0L,
  val remainingBalanceCents: Long,
  val installmentAmountCents: Long = 0L,
  val remainingInstallmentsCount: Int = 0,
  val frequency: RepaymentFrequency = RepaymentFrequency.UNKNOWN,
  val customIntervalDays: Int? = null,
  val lastKnownPaidDate: String? = null,
  val nextDueDate: String? = null,
  val dateStatus: DueDateStatus = DueDateStatus.UNCONFIRMED,
  val status: DebtStatus = DebtStatus.ACTIVE,
  val isManuallyEntered: Boolean = true,
  val notes: String = "",
  val createdAtEpochMillis: Long = System.currentTimeMillis(),
  val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
