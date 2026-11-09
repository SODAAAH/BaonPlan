package com.baonplan.finance.data

import com.baonplan.finance.data.backup.BackupSerializer
import com.baonplan.finance.data.backup.BaonPlanBackupPayload
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializerTest {

  @Test
  fun jsonBackupRoundTrip_preservesAllRecordsAndCentavos() {
    val sampleDebts = listOf(
      DebtEntity(
        id = 1L,
        name = "Sample Installment Loan",
        lender = "Sample Lender",
        category = "Cash Loan",
        remainingBalanceCents = 44_455L,
        installmentAmountCents = 2_615L,
        remainingInstallmentsCount = 17,
        frequency = RepaymentFrequency.WEEKLY,
        nextDueDate = "2026-11-16",
        dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
        status = DebtStatus.ACTIVE
      ),
      DebtEntity(
        id = 2L,
        name = "Sample Completed Loan",
        lender = "Sample Lender",
        category = "E-Wallet",
        originalPrincipalCents = 50_000L,
        priorPaidCents = 50_000L,
        isPriorPaidKnown = true,
        remainingBalanceCents = 0L,
        status = DebtStatus.PAID
      )
    )
    val sampleInstallments = listOf(
      InstallmentEntity(
        id = 1L,
        debtId = 1L,
        installmentNumber = 1,
        dueDate = "2026-11-16",
        amountDueCents = 2_615L,
        dateStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION,
        status = InstallmentStatus.UPCOMING
      )
    )

    val payload = BaonPlanBackupPayload(
      settings = DefaultSettingsProvider.defaultSettings(),
      debts = sampleDebts,
      installments = sampleInstallments,
      transactions = listOf(
        TransactionEntity(
          id = 1L,
          type = TransactionType.ALLOWANCE,
          title = "Weekly Baon",
          amountCents = 40_000L,
          date = "2026-11-09"
        )
      ),
      allowanceRecords = emptyList(),
      expenses = emptyList(),
      debtReserves = emptyList()
    )

    val json = BackupSerializer.encodeBackupJson(payload)
    val decoded = BackupSerializer.decodeBackupJson(json).getOrThrow()

    assertEquals(2, decoded.debts.size)
    assertEquals(1, decoded.installments.size)
    assertEquals(44_455L, decoded.debts.filter { it.remainingBalanceCents > 0 }.sumOf { it.remainingBalanceCents })
    assertEquals(40_000L, decoded.transactions.first().amountCents)
  }

  @Test
  fun csvExport_escapesFieldsAndFormatsPesoAmountsProperly() {
    val csv = BackupSerializer.encodeTransactionsCsv(
      listOf(
        TransactionEntity(
          id = 7L,
          type = TransactionType.DEBT_REPAYMENT,
          title = "Wallet Loan, Weekly",
          amountCents = 2_615L,
          date = "2026-11-16",
          debtId = 2L,
          fundedFromReserveCents = 2_615L,
          notes = "Confirmed \"installment\""
        )
      )
    )
    assertTrue(csv.contains("id,date,type,title,amount_php,amount_centavos"))
    assertTrue(csv.contains("\"Wallet Loan, Weekly\",26.15,2615"))
    assertTrue(csv.contains("\"Confirmed \"\"installment\"\"\""))
  }
}
