package com.baonplan.finance.domain.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.FinancialCalculator
import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.InstallmentStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.ObligationRole
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OcrAccuracyAndReasoningTest {

  @Test
  fun normalizerStripsCircularEmblemsRepairsCurrencyDigitsAndAuditsChanges() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1080,
      height = 1920,
      lines = listOf(
        OcrLine("G)) P1,000.00 GCash Cash Loan", OcrBoundingBox(60, 200, 740, 240)),
        OcrLine("Remaining: ₱1,O87.38", OcrBoundingBox(60, 300, 600, 340)),
        // Duplicate overlapping box from ML Kit multi-pass block
        OcrLine("Remaining: ₱1,O87.38", OcrBoundingBox(62, 301, 598, 339)),
        OcrLine("Installment: ₱51,78", OcrBoundingBox(60, 380, 500, 420)),
        OcrLine("Page 1 of 2", OcrBoundingBox(60, 460, 300, 495))
      )
    )

    val (rows, audits) = OcrLayoutNormalizer.normalizeWithAudits(page)

    assertEquals(4, rows.size) // Overlapping duplicate removed
    assertEquals("₱1,000.00 GCash Cash Loan", rows[0].text)
    assertEquals("Remaining: ₱1,087.38", rows[1].text)
    assertEquals("Installment: ₱51.78", rows[2].text)
    // Plain word "Page 1 of 2" must NOT be corrupted into "₱age"
    assertEquals("Page 1 of 2", rows[3].text)

    assertTrue(audits.any { it.rawText.contains("G))") && it.normalizedText == "P1,000.00 GCash Cash Loan" })
    assertTrue(audits.any { it.rawText == "P1,000.00 GCash Cash Loan" && it.normalizedText == "₱1,000.00 GCash Cash Loan" })
    assertTrue(audits.any { it.rawText.contains("1,O87.38") && it.normalizedText.contains("1,087.38") })
    assertTrue(audits.any { it.rawText.contains("51,78") && it.normalizedText.contains("51.78") })
  }

  @Test
  fun variableInstallmentSchedulePreventsConstantMultiplicationAssumption() {
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SCHEDULE_ONLY,
      installmentAmountCents = ExtractedField.extracted(50_000L, "First installment ₱500.00", 0),
      remainingInstallmentsCount = ExtractedField.extracted(3, "3 remaining", 0),
      installmentRows = listOf(
        ExtractedInstallmentRow("r1", dueDateIso = "2026-11-15", amountCents = 50_000L, status = ExtractedInstallmentStatus.UPCOMING),
        ExtractedInstallmentRow("r2", dueDateIso = "2026-12-15", amountCents = 65_000L, status = ExtractedInstallmentStatus.UPCOMING),
        ExtractedInstallmentRow("r3", dueDateIso = "2027-01-15", amountCents = 80_000L, status = ExtractedInstallmentStatus.UPCOMING)
      )
    )

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw)

    // Variable schedule rows sum to ₱1,950.00 (195_000L), NOT 3 * ₱500.00 = ₱1,500.00!
    assertEquals(195_000L, enriched.remainingInstallmentTotalCents.value)
    assertEquals(FieldOrigin.CALCULATED, enriched.remainingInstallmentTotalCents.origin)
  }

  @Test
  fun arithmeticConflictFlagsLowConfidenceWithoutOverwritingExtractedRemainingTotal() {
    // Extracted Remaining Total = ₱600.00, while 7 remaining * ₱100.00 = ₱700.00
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SUMMARY_ONLY,
      installmentAmountCents = ExtractedField.extracted(10_000L, "Installment ₱100.00", 0),
      remainingInstallmentsCount = ExtractedField.extracted(7, "Remaining 7", 0),
      remainingInstallmentTotalCents = ExtractedField.extracted(60_000L, "Remaining total ₱600.00", 0)
    )

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw)

    // Preserves extracted ₱600.00, lowers confidence, preserves ₱700.00 alternative, and warns
    assertEquals(60_000L, enriched.remainingInstallmentTotalCents.value)
    assertEquals(FieldOrigin.EXTRACTED, enriched.remainingInstallmentTotalCents.origin)
    assertEquals(ExtractionConfidence.LOW, enriched.remainingInstallmentTotalCents.confidence)
    assertTrue(enriched.remainingInstallmentTotalCents.hasConflict)
    assertTrue(enriched.remainingInstallmentTotalCents.alternatives.any { it.value == 70_000L })
    assertTrue(enriched.issues.any { it.fieldKey == "remainingInstallmentTotalCents" })
  }

  @Test
  fun classifiesDailyQuarterlyAndTwiceMonthlyFrequenciesAccurately() {
    val daily = FinancialReasoningEngine.enrichSinglePage(
      SingleScreenshotExtraction(
        screenshotIndex = 0,
        documentKind = ExtractedDocumentKind.SCHEDULE_ONLY,
        installmentRows = listOf(
          ExtractedInstallmentRow("d1", dueDateIso = "2026-11-01"),
          ExtractedInstallmentRow("d2", dueDateIso = "2026-11-02"),
          ExtractedInstallmentRow("d3", dueDateIso = "2026-11-03"),
          ExtractedInstallmentRow("d4", dueDateIso = "2026-11-04")
        )
      )
    )
    assertEquals(RepaymentFrequency.DAILY, daily.frequency.value)

    val quarterly = FinancialReasoningEngine.enrichSinglePage(
      SingleScreenshotExtraction(
        screenshotIndex = 0,
        documentKind = ExtractedDocumentKind.SCHEDULE_ONLY,
        installmentRows = listOf(
          ExtractedInstallmentRow("q1", dueDateIso = "2026-01-15"),
          ExtractedInstallmentRow("q2", dueDateIso = "2026-04-15"),
          ExtractedInstallmentRow("q3", dueDateIso = "2026-07-15"),
          ExtractedInstallmentRow("q4", dueDateIso = "2026-10-15")
        )
      )
    )
    assertEquals(RepaymentFrequency.QUARTERLY, quarterly.frequency.value)
  }

  @Test
  fun financialCalculatorPreventsDoubleCountingLinkedStatementsAndConvertedPlans() {
    val creditLine = DebtEntity(
      id = 1L,
      name = "CIMB REVI Credit",
      lender = "CIMB Bank PH",
      category = "Revolving Credit Line",
      productClassification = LoanProductClassification.REVOLVING_CREDIT,
      obligationRole = ObligationRole.STANDALONE,
      parentDebtId = null,
      remainingBalanceCents = 1_000_000L, // ₱10,000 total credit line balance
      installmentAmountCents = 500_000L,
      remainingInstallmentsCount = 1,
      frequency = RepaymentFrequency.MONTHLY,
      nextDueDate = "2026-11-25",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE
    )
    val linkedStatement = DebtEntity(
      id = 2L,
      name = "CIMB REVI Nov Statement",
      lender = "CIMB Bank PH",
      category = "Billing Statement",
      productClassification = LoanProductClassification.BNPL_PAY_LATER,
      obligationRole = ObligationRole.BILLING_STATEMENT,
      parentDebtId = 1L, // Linked to parent credit line -> must NOT double-count!
      remainingBalanceCents = 600_000L,
      installmentAmountCents = 600_000L,
      remainingInstallmentsCount = 1,
      frequency = RepaymentFrequency.ONE_TIME,
      nextDueDate = "2026-11-25",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE
    )
    val linkedConvertedPlan = DebtEntity(
      id = 3L,
      name = "CIMB REVI Converted Plan",
      lender = "CIMB Bank PH",
      category = "Converted Installment",
      productClassification = LoanProductClassification.CREDIT_BILL_INSTALLMENT,
      obligationRole = ObligationRole.CONVERTED_INSTALLMENT,
      parentDebtId = 1L, // Linked to parent credit line -> must NOT double-count!
      remainingBalanceCents = 400_000L,
      installmentAmountCents = 100_000L,
      remainingInstallmentsCount = 4,
      frequency = RepaymentFrequency.MONTHLY,
      nextDueDate = "2026-12-25",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE
    )
    val standaloneStatement = DebtEntity(
      id = 4L,
      name = "Standalone Credit Statement",
      lender = "Other Bank",
      category = "Billing Statement",
      productClassification = LoanProductClassification.BNPL_PAY_LATER,
      obligationRole = ObligationRole.STANDALONE,
      parentDebtId = null, // No parent in app -> MUST count!
      remainingBalanceCents = 250_000L,
      installmentAmountCents = 250_000L,
      remainingInstallmentsCount = 1,
      frequency = RepaymentFrequency.ONE_TIME,
      nextDueDate = "2026-11-30",
      dateStatus = DueDateStatus.CONFIRMED,
      status = DebtStatus.ACTIVE
    )

    val allDebts = listOf(creditLine, linkedStatement, linkedConvertedPlan, standaloneStatement)

    // Total active debt = ₱10,000 (credit line) + ₱2,500 (standalone statement) = ₱12,500 (1,250,000L), NOT ₱22,500!
    assertEquals(1_250_000L, FinancialCalculator.calculateTotalActiveDebt(allDebts))
    assertTrue(FinancialCalculator.countsTowardTotalDebt(creditLine))
    assertFalse(FinancialCalculator.countsTowardTotalDebt(linkedStatement))
    assertFalse(FinancialCalculator.countsTowardTotalDebt(linkedConvertedPlan))
    assertTrue(FinancialCalculator.countsTowardTotalDebt(standaloneStatement))

    val today = LocalDate.of(2026, 11, 10)
    val overview = FinancialCalculator.buildPaymentOverview(allDebts, emptyList<InstallmentEntity>(), today)
    assertEquals(750_000L, overview.upcomingDatedPayments.sumOf { it.amountCents }) // ₱5,000 + ₱2,500 (excludes linked ₱6,000 and ₱4,000)
  }
}
