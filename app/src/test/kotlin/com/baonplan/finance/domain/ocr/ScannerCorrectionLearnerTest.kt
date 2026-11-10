package com.baonplan.finance.domain.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.domain.model.DueDateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScannerCorrectionLearnerTest {

  private fun baseDraft(
    cues: List<DetectedLabelValueCue> = emptyList(),
    providerProfileId: String? = "billease",
    nextDueDateIso: String? = null,
    numericOrder: NumericDateOrder = NumericDateOrder.AMBIGUOUS
  ): DebtImportDraft = DebtImportDraft(
    candidateId = "loan_group_1",
    sourceScreenshotIndices = listOf(0),
    providerProfileId = providerProfileId,
    lender = ExtractedField.extracted("BillEase", screenshotIndex = 0),
    loanName = ExtractedField.extracted("BillEase Loan", screenshotIndex = 0),
    loanType = ExtractedField.extracted("Installment Loan", screenshotIndex = 0),
    loanReferenceId = ExtractedField.notDetected(),
    originalPrincipalCents = ExtractedField.notDetected(),
    amountReceivedCents = ExtractedField.notDetected(),
    outstandingBalanceCents = ExtractedField.extracted(120_000L, "Remaining Balance ₱1,200.00", 0),
    amountCurrentlyDueCents = ExtractedField.notDetected(),
    remainingInstallmentTotalCents = ExtractedField.notDetected(),
    calculatedUnpaidScheduleSumCents = ExtractedField.notDetected(),
    calculatedPaidScheduleSumCents = ExtractedField.notDetected(),
    installmentAmountCents = ExtractedField.notDetected(),
    originalInstallmentCount = ExtractedField.notDetected(),
    paidInstallmentsCount = ExtractedField.notDetected(),
    remainingInstallmentsCount = ExtractedField.notDetected(),
    frequency = ExtractedField.notDetected(),
    customIntervalDays = ExtractedField.notDetected(),
    nextDueDateIso = if (nextDueDateIso != null) {
      ExtractedField.extracted(nextDueDateIso, screenshotIndex = 0)
    } else {
      ExtractedField.notDetected()
    },
    finalDueDateIso = ExtractedField.notDetected(),
    lastPaidDateIso = ExtractedField.notDetected(),
    totalRepaymentCents = ExtractedField.notDetected(),
    interestCents = ExtractedField.notDetected(),
    serviceFeeCents = ExtractedField.notDetected(),
    processingFeeCents = ExtractedField.notDetected(),
    otherFeesCents = ExtractedField.notDetected(),
    loanStatus = ExtractedField.notDetected(),
    dueDateStatus = DueDateStatus.UNCONFIRMED,
    installmentRows = emptyList(),
    detectedLabelCues = cues,
    numericDateOrderUsed = numericOrder
  )

  @Test
  fun learnsGenericFieldMappingProviderDateOrderAndCategoryWithoutStoringPii() {
    val draft = baseDraft(
      nextDueDateIso = "2026-04-05",
      numericOrder = NumericDateOrder.MONTH_FIRST,
      cues = listOf(
        DetectedLabelValueCue(
          normalizedLabel = "financed capital",
          assignedField = LoanFieldKey.INTEREST,
          moneyCents = 250_000L
        )
      )
    )
    val confirmedDebt = DebtEntity(
      name = "Juan Dela Cruz Laptop Loan",
      lender = "BillEase",
      category = "Education",
      originalPrincipalCents = 250_000L, // User moved ₱2,500.00 from "financed capital" into Original Principal
      remainingBalanceCents = 120_000L,
      nextDueDate = "2026-05-04" // User swapped 04/05 -> 05/04 (DAY_FIRST)
    )

    val (updated, didLearn) = ScannerCorrectionLearner.learnFromSave(
      currentPrefs = LearnedScannerPreferences(),
      initialDraft = draft,
      confirmedDebt = confirmedDebt
    )

    assertTrue(didLearn)
    assertEquals(LoanFieldKey.ORIGINAL_PRINCIPAL, updated.labelOverrides["financed capital"])
    assertEquals(NumericDateOrder.DAY_FIRST.name, updated.providerDateOrder["billease"])
    assertEquals("Education", updated.providerCategoryDefaults["billease"])
    assertEquals(1, updated.correctionCount)

    // Privacy invariant: no peso amounts, loan titles, or numeric dates persisted
    val serializedSnapshot = updated.toString()
    assertFalse(serializedSnapshot.contains("250000"))
    assertFalse(serializedSnapshot.contains("2500"))
    assertFalse(serializedSnapshot.contains("Juan Dela Cruz"))
    assertFalse(serializedSnapshot.contains("2026-05-04"))

    // Verify learned overrides work on a subsequent screenshot extraction
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase"),
        OcrLine("Financed Capital: ₱3,500.00"),
        OcrLine("Remaining Balance: ₱1,500.00"),
        OcrLine("Next Due Date: 06/07/2026")
      )
    )
    val reExtracted = DebtOcrExtractor.extract(page, LocalDate.of(2026, 11, 10), updated)
    assertEquals(350_000L, reExtracted.originalPrincipalCents.value)
    assertEquals("2026-07-06", reExtracted.nextDueDateIso.value)
    assertEquals("Education", reExtracted.loanType.value)
  }

  @Test
  fun skipsLearningWhenLabelContainsDigitsOrMultipleLabelsShareSameValue() {
    val draft = baseDraft(
      cues = listOf(
        DetectedLabelValueCue("order 9981 total", LoanFieldKey.INTEREST, moneyCents = 200_000L),
        DetectedLabelValueCue("total due", LoanFieldKey.TOTAL_REPAYMENT, moneyCents = 150_000L),
        DetectedLabelValueCue("amount owed", LoanFieldKey.OTHER_FEES, moneyCents = 150_000L)
      )
    )
    val confirmedDebt = DebtEntity(
      name = "Test Debt",
      lender = "BillEase",
      category = "Installment Loan",
      originalPrincipalCents = 200_000L, // only matches digit-containing label -> must skip!
      serviceFeeCents = 150_000L, // matches TWO distinct labels ("total due" & "amount owed") -> must skip!
      remainingBalanceCents = 120_000L
    )

    val (updated, didLearn) = ScannerCorrectionLearner.learnFromSave(
      currentPrefs = LearnedScannerPreferences(),
      initialDraft = draft,
      confirmedDebt = confirmedDebt
    )

    assertFalse(didLearn)
    assertTrue(updated.labelOverrides.isEmpty())
  }
}
