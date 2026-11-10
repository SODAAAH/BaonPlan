package com.baonplan.finance.data

import com.baonplan.finance.data.backup.BackupSerializer
import com.baonplan.finance.data.backup.BaonPlanBackupPayload
import com.baonplan.finance.data.local.DefaultSettingsProvider
import com.baonplan.finance.data.local.entity.BudgetAllocationEntity
import com.baonplan.finance.data.local.entity.BudgetProfileEntity
import com.baonplan.finance.data.local.entity.DebtAttachmentEntity
import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.IncomeOccurrenceEntity
import com.baonplan.finance.data.local.entity.IncomeSourceEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.data.local.entity.TransactionEntity
import com.baonplan.finance.domain.model.BudgetCategoryKey
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.IncomeFrequency
import com.baonplan.finance.domain.model.IncomeOccurrenceStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.MonthlyCycleMode
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.TransactionType
import com.baonplan.finance.domain.model.WeeklyCycleMode
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

  @Test
  fun legacySchemaVersion1BackupJsonDecodesCleanlyAndV2PreservesAttachments() {
    val legacyV1Json = """
      {
        "schemaVersion": 1,
        "appId": "com.baonplan.finance",
        "currency": "PHP",
        "timezone": "Asia/Manila",
        "exportedAtIso": "2026-11-01T00:00:00Z",
        "settings": {
          "id": 1,
          "minWeeklyAllowanceCents": 40000,
          "maxWeeklyAllowanceCents": 50000,
          "conservativeAllowanceCents": 40000,
          "schoolDaysPerWeek": 2,
          "foodPerSchoolDayCents": 5000,
          "weeklyFoodBudgetCents": 10000,
          "weeklyOtherExpensesBudgetCents": 5000,
          "weeklyEmergencySavingsTargetCents": 5000,
          "weeklyDebtReserveTargetCents": 15000,
          "remindersEnabled": true,
          "allowanceReminderDayOfWeek": 1,
          "reminderHour": 19,
          "reminderMinute": 0,
          "isManuallyEntered": true
        },
        "debts": [],
        "installments": [],
        "transactions": [],
        "allowanceRecords": [],
        "expenses": [],
        "debtReserves": []
      }
    """.trimIndent()

    val decodedV1 = BackupSerializer.decodeBackupJson(legacyV1Json).getOrThrow()
    assertEquals(1, decodedV1.schemaVersion)
    assertTrue(decodedV1.debtAttachments.isEmpty())

    val payloadV2 = BaonPlanBackupPayload(
      schemaVersion = 2,
      settings = DefaultSettingsProvider.defaultSettings(),
      debts = emptyList(),
      installments = emptyList(),
      transactions = emptyList(),
      allowanceRecords = emptyList(),
      expenses = emptyList(),
      debtReserves = emptyList(),
      debtAttachments = listOf(
        DebtAttachmentEntity(
          id = 1L,
          debtId = 99L,
          fileName = "debt_99_s0.jpg",
          displayLabel = "Screenshot #1",
          screenshotIndex = 0,
          createdAtEpochMillis = 123456L
        )
      )
    )
    val encodedV2 = BackupSerializer.encodeBackupJson(payloadV2)
    val decodedV2 = BackupSerializer.decodeBackupJson(encodedV2).getOrThrow()
    assertEquals(2, decodedV2.schemaVersion)
    assertEquals(1, decodedV2.debtAttachments.size)
    assertEquals("debt_99_s0.jpg", decodedV2.debtAttachments.single().fileName)

    val payloadV3 = BaonPlanBackupPayload(
      settings = DefaultSettingsProvider.defaultSettings(),
      debts = listOf(
        DebtEntity(
          id = 10L,
          name = "CIMB REVI Credit",
          lender = "CIMB Bank PH",
          category = "Revolving Credit Line",
          productClassification = com.baonplan.finance.domain.model.LoanProductClassification.REVOLVING_CREDIT,
          creditLimitCents = 2_000_000L,
          availableCreditCents = 1_500_000L,
          usedCreditCents = 500_000L,
          statementBalanceCents = 500_000L,
          minimumDueCents = 25_000L,
          remainingBalanceCents = 500_000L
        )
      ),
      installments = emptyList(),
      transactions = emptyList(),
      allowanceRecords = emptyList(),
      expenses = emptyList(),
      debtReserves = emptyList()
    )
    val decodedV3 = BackupSerializer.decodeBackupJson(BackupSerializer.encodeBackupJson(payloadV3)).getOrThrow()
    assertEquals(5, decodedV3.schemaVersion)
    assertEquals(
      com.baonplan.finance.domain.model.LoanProductClassification.REVOLVING_CREDIT,
      decodedV3.debts.single().productClassification
    )
    assertEquals(2_000_000L, decodedV3.debts.single().creditLimitCents)
    assertEquals(500_000L, decodedV3.debts.single().statementBalanceCents)
  }

  @Test
  fun schemaVersion5_budgetProfileIncomeAndAllocationsRoundTripCleanly() {
    val payload = BaonPlanBackupPayload(
      schemaVersion = 5,
      settings = DefaultSettingsProvider.defaultSettings(),
      debts = emptyList(),
      installments = emptyList(),
      transactions = emptyList(),
      allowanceRecords = emptyList(),
      expenses = emptyList(),
      debtReserves = emptyList(),
      budgetProfile = BudgetProfileEntity(
        id = 1,
        isConfigured = true,
        weeklyCycleMode = WeeklyCycleMode.MONDAY_TO_SUNDAY,
        monthlyCycleMode = MonthlyCycleMode.PAYDAY_CYCLE,
        rolloverEnabled = true,
        reserveAheadEnabled = true,
        primarySourceId = 7L
      ),
      incomeSources = listOf(
        IncomeSourceEntity(
          id = 7L,
          name = "Scholarship & Baon",
          frequency = IncomeFrequency.TWICE_MONTHLY,
          expectedAmountCents = 400_000L,
          startDateIso = "2026-11-01",
          isPrimary = true
        )
      ),
      incomeOccurrences = listOf(
        IncomeOccurrenceEntity(
          id = 11L,
          sourceId = 7L,
          sourceNameSnapshot = "Scholarship & Baon",
          expectedDateIso = "2026-11-15",
          expectedAmountCents = 400_000L,
          actualReceivedAmountCents = 350_000L,
          actualReceivedDateIso = "2026-11-15",
          status = IncomeOccurrenceStatus.PARTIALLY_RECEIVED
        )
      ),
      budgetAllocations = listOf(
        BudgetAllocationEntity(
          id = 1L,
          categoryKey = BudgetCategoryKey.FOOD,
          allocatedAmountCents = 150_000L,
          isEnabled = true
        ),
        BudgetAllocationEntity(
          id = 2L,
          categoryKey = BudgetCategoryKey.SAVINGS,
          allocatedAmountCents = 50_000L,
          isEnabled = true
        )
      )
    )

    val json = BackupSerializer.encodeBackupJson(payload)
    val decoded = BackupSerializer.decodeBackupJson(json).getOrThrow()

    assertEquals(5, decoded.schemaVersion)
    val profile = decoded.budgetProfile!!
    assertTrue(profile.isConfigured)
    assertTrue(profile.reserveAheadEnabled)
    assertEquals(1, decoded.incomeSources.size)
    assertEquals(IncomeFrequency.TWICE_MONTHLY, decoded.incomeSources.single().frequency)
    assertEquals(1, decoded.incomeOccurrences.size)
    assertEquals(350_000L, decoded.incomeOccurrences.single().actualReceivedAmountCents)
    assertEquals(2, decoded.budgetAllocations.size)
    assertEquals(BudgetCategoryKey.SAVINGS, decoded.budgetAllocations[1].categoryKey)
  }
}
