package com.baonplan.finance.domain.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtImportValidatorAndDuplicateTest {

  @Test
  fun blocksPaidGreaterThanOriginalNegativeAndMissingConfirmedDate() {
    val issues = DebtImportValidator.validateValues(
      debtName = "SPayLater Loan",
      remainingBalanceCents = -100L,
      installmentAmountCents = 0L,
      originalInstallmentCount = 4,
      paidInstallmentsCount = 5,
      remainingInstallmentsCount = -1,
      frequency = RepaymentFrequency.CUSTOM_DAYS,
      customIntervalDays = null,
      nextDueDateIso = null,
      dueDateStatus = DueDateStatus.CONFIRMED,
      requireNonBlankName = true
    )

    val errorKeys = issues.filter { it.severity == ImportSeverity.ERROR }.map { it.fieldKey }.toSet()
    assertTrue("remainingBalanceCents" in errorKeys)
    assertTrue("installmentAmountCents" in errorKeys)
    assertTrue("paidInstallmentsCount" in errorKeys)
    assertTrue("remainingInstallmentsCount" in errorKeys)
    assertTrue("customIntervalDays" in errorKeys)
    assertTrue("nextDueDate" in errorKeys)
  }

  @Test
  fun warnsOnBalanceVsRemainingInstallmentTotalAndScheduleSumWithoutMutatingValues() {
    val rows = listOf(
      ExtractedInstallmentRow(
        id = "r1",
        dueDateIso = "2026-11-16",
        amountCents = 60_000L,
        status = ExtractedInstallmentStatus.UPCOMING
      ),
      ExtractedInstallmentRow(
        id = "r2",
        dueDateIso = null,
        amountCents = 60_000L,
        status = ExtractedInstallmentStatus.UPCOMING
      )
    )

    val issues = DebtImportValidator.validateValues(
      debtName = "BillEase Laptop",
      remainingBalanceCents = 150_000L,
      explicitBalanceCents = 150_000L,
      explicitRemainingInstallmentTotalCents = 180_000L,
      originalInstallmentCount = 6,
      paidInstallmentsCount = 3,
      remainingInstallmentsCount = 2, // 6 - 3 != 2
      scheduleRows = rows
    )

    val warningKeys = issues.filter { it.severity == ImportSeverity.WARNING }.map { it.fieldKey }.toSet()
    assertTrue("balanceVsInstallmentTotal" in warningKeys)
    assertTrue("countConsistency" in warningKeys)
    assertTrue("scheduleSumVsBalance" in warningKeys)
    assertTrue("scheduleRowGaps" in warningKeys)
    assertEquals(0, issues.count { it.severity == ImportSeverity.ERROR })
  }

  @Test
  fun warnsWhenAmountCurrentlyDueExceedsRemainingBalanceWithoutOverwriting() {
    val issues = DebtImportValidator.validateValues(
      debtName = "Maya Credit",
      remainingBalanceCents = 100_000L,
      explicitBalanceCents = 100_000L,
      explicitAmountCurrentlyDueCents = 115_000L,
      requireNonBlankName = true
    )

    val warningKeys = issues.filter { it.severity == ImportSeverity.WARNING }.map { it.fieldKey }.toSet()
    assertTrue("amountDueVsBalance" in warningKeys)
    assertEquals(0, issues.count { it.severity == ImportSeverity.ERROR })
  }

  @Test
  fun detectsDuplicateLoanWithoutFalsePositiveOnDistinctLoanFromSameLender() {
    val existingSpayLaterOrder1 = DebtEntity(
      id = 10L,
      name = "Shopee SPayLater #SP-1122",
      lender = "Shopee (SPayLater)",
      category = "BNPL",
      originalPrincipalCents = 180_000L,
      remainingBalanceCents = 120_000L,
      installmentAmountCents = 40_000L,
      nextDueDate = "2026-11-15",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE,
      notes = "Ref: SP-1122"
    )
    val existingInsts = listOf(
      InstallmentEntity(
        id = 1L,
        debtId = 10L,
        installmentNumber = 1,
        dueDate = "2026-11-15",
        amountDueCents = 40_000L
      )
    )

    // Exact duplicate screenshot -> MUST flag
    val duplicateMatches = DebtDuplicateDetector.findPotentialDuplicates(
      lender = "Shopee (SPayLater)",
      loanName = "Shopee SPayLater #SP-1122",
      loanReferenceId = "SP-1122",
      remainingBalanceCents = 120_000L,
      originalPrincipalCents = 180_000L,
      installmentAmountCents = 40_000L,
      nextDueDateIso = "2026-11-15",
      scheduleDates = setOf("2026-11-15"),
      existingDebts = listOf(existingSpayLaterOrder1),
      existingInstallments = existingInsts
    )
    assertEquals(1, duplicateMatches.size)
    assertEquals(DuplicateConfidence.HIGH, duplicateMatches.single().confidence)

    // Genuinely separate loan from same lender -> ZERO false positive!
    val separateLoanMatches = DebtDuplicateDetector.findPotentialDuplicates(
      lender = "Shopee (SPayLater)",
      loanName = "Winter Jacket Order #SP-9901",
      loanReferenceId = "SP-9901",
      remainingBalanceCents = 290_000L,
      originalPrincipalCents = 290_000L,
      installmentAmountCents = 96_667L,
      nextDueDateIso = "2026-12-05",
      scheduleDates = setOf("2026-12-05", "2027-01-05"),
      existingDebts = listOf(existingSpayLaterOrder1),
      existingInstallments = existingInsts
    )
    assertTrue(separateLoanMatches.isEmpty())
  }
}
