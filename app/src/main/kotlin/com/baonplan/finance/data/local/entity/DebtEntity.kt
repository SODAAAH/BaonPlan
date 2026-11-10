package com.baonplan.finance.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.ObligationRole
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
  val updatedAtEpochMillis: Long = System.currentTimeMillis(),
  // Universal financial & multi-product fields (v3)
  @ColumnInfo(defaultValue = "UNKNOWN")
  val productClassification: LoanProductClassification = LoanProductClassification.UNKNOWN,
  val applicationProvider: String? = null,
  val actualCreditor: String? = null,
  val productName: String? = null,
  val loanReferenceId: String? = null,
  val loanBookingDateIso: String? = null,
  val disbursedAmountCents: Long? = null,
  val otherChargesCents: Long? = null,
  val outstandingPrincipalCents: Long? = null,
  val remainingInstallmentTotalCents: Long? = null,
  val amountCurrentlyDueCents: Long? = null,
  val pastDueAmountCents: Long? = null,
  val unpaidChargesCents: Long? = null,
  val creditLimitCents: Long? = null,
  val availableCreditCents: Long? = null,
  val usedCreditCents: Long? = null,
  val statementBalanceCents: Long? = null,
  val minimumDueCents: Long? = null,
  val statementDateIso: String? = null,
  val statementPeriod: String? = null,
  val originalInstallmentsCount: Int? = null,
  val paidInstallmentsCount: Int? = null,
  val parentDebtId: Long? = null,
  @ColumnInfo(defaultValue = "STANDALONE")
  val obligationRole: ObligationRole = ObligationRole.STANDALONE,
  @ColumnInfo(defaultValue = "1")
  val scheduleVersion: Int = 1,
  val scheduleEffectiveDateIso: String? = null
)
