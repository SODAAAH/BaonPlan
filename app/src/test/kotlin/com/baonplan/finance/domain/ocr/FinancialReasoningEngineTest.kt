package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialReasoningEngineTest {

  @Test
  fun derivesRemainingCountAndCalculatedRemainingInstallmentTotalSeparately() {
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SUMMARY_ONLY,
      outstandingBalanceCents = ExtractedField.extracted(105_000L, "Outstanding Balance ₱1,050.00", 0),
      installmentAmountCents = ExtractedField.extracted(5_178L, "Weekly Payment ₱51.78", 0),
      originalInstallmentCount = ExtractedField.extracted(24, "Total installments 24", 0),
      paidInstallmentsCount = ExtractedField.extracted(3, "Paid installments 3", 0)
    )

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw)

    assertEquals(21, enriched.remainingInstallmentsCount.value)
    assertEquals(FieldOrigin.CALCULATED, enriched.remainingInstallmentsCount.origin)
    // 51.78 * 21 = 1087.38
    assertEquals(108_738L, enriched.remainingInstallmentTotalCents.value)
    assertEquals(FieldOrigin.CALCULATED, enriched.remainingInstallmentTotalCents.origin)
    // Never silently overwrites extracted outstandingBalance!
    assertEquals(105_000L, enriched.outstandingBalanceCents.value)
    assertEquals(FieldOrigin.EXTRACTED, enriched.outstandingBalanceCents.origin)
  }

  @Test
  fun solvesOriginalCountFromPaidPlusRemainingAndTotalRepaymentFromPrincipalPlusFees() {
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SUMMARY_ONLY,
      originalPrincipalCents = ExtractedField.extracted(300_000L, "Principal ₱3,000.00", 0),
      interestCents = ExtractedField.extracted(30_000L, "Interest ₱300.00", 0),
      serviceFeeCents = ExtractedField.extracted(15_000L, "Service Fee ₱150.00", 0),
      processingFeeCents = ExtractedField.extracted(10_000L, "Processing Fee ₱100.00", 0),
      paidInstallmentsCount = ExtractedField.extracted(7, "Paid 7", 0),
      remainingInstallmentsCount = ExtractedField.extracted(17, "Remaining 17", 0)
    )

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw)

    assertEquals(24, enriched.originalInstallmentCount.value)
    assertEquals(FieldOrigin.CALCULATED, enriched.originalInstallmentCount.origin)
    assertEquals(355_000L, enriched.totalRepaymentCents.value)
    assertEquals(FieldOrigin.CALCULATED, enriched.totalRepaymentCents.origin)
  }

  @Test
  fun infersWeeklyWithHolidayJitterAndExplainsIntervalPattern() {
    val rows = listOf(
      ExtractedInstallmentRow("r1", dueDateIso = "2026-10-02", amountCents = 5_178L, status = ExtractedInstallmentStatus.PAID, dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("r2", dueDateIso = "2026-10-09", amountCents = 5_178L, status = ExtractedInstallmentStatus.PAID, dateConfidence = ExtractionConfidence.HIGH), // +7d
      ExtractedInstallmentRow("r3", dueDateIso = "2026-10-17", amountCents = 5_178L, status = ExtractedInstallmentStatus.UPCOMING, dateConfidence = ExtractionConfidence.HIGH), // +8d (1d shift)
      ExtractedInstallmentRow("r4", dueDateIso = "2026-10-23", amountCents = 5_178L, status = ExtractedInstallmentStatus.UPCOMING, dateConfidence = ExtractionConfidence.HIGH) // +6d (back on Fri anchor)
    )
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SCHEDULE_ONLY,
      installmentRows = rows
    )

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw)

    assertEquals(RepaymentFrequency.WEEKLY, enriched.frequency.value)
    assertEquals(FieldOrigin.ESTIMATED, enriched.frequency.origin)
    assertEquals(ExtractionConfidence.MEDIUM, enriched.frequency.confidence)
    assertNotNull(enriched.frequency.verificationReason)
    assertTrue(enriched.frequency.verificationReason!!.contains("6 to 8"))
  }

  @Test
  fun infersBiweeklyAndShiftedMonthlyWhileRefusingToForceIrregularDates() {
    val biweeklyRows = listOf(
      ExtractedInstallmentRow("b1", dueDateIso = "2026-10-01", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("b2", dueDateIso = "2026-10-16", dateConfidence = ExtractionConfidence.HIGH), // +15d
      ExtractedInstallmentRow("b3", dueDateIso = "2026-10-29", dateConfidence = ExtractionConfidence.HIGH), // +13d
      ExtractedInstallmentRow("b4", dueDateIso = "2026-11-12", dateConfidence = ExtractionConfidence.HIGH) // +14d
    )
    val biweekly = FinancialReasoningEngine.enrichSinglePage(
      SingleScreenshotExtraction(0, ExtractedDocumentKind.SCHEDULE_ONLY, installmentRows = biweeklyRows)
    )
    assertEquals(RepaymentFrequency.EVERY_14_DAYS, biweekly.frequency.value)
    assertEquals(FieldOrigin.ESTIMATED, biweekly.frequency.origin)

    val shiftedMonthlyRows = listOf(
      ExtractedInstallmentRow("m1", dueDateIso = "2026-11-16", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("m2", dueDateIso = "2026-12-17", dateConfidence = ExtractionConfidence.HIGH), // weekend shift +1d
      ExtractedInstallmentRow("m3", dueDateIso = "2027-01-15", dateConfidence = ExtractionConfidence.HIGH), // -1d shift
      ExtractedInstallmentRow("m4", dueDateIso = "2027-02-16", dateConfidence = ExtractionConfidence.HIGH)
    )
    val monthly = FinancialReasoningEngine.enrichSinglePage(
      SingleScreenshotExtraction(0, ExtractedDocumentKind.SCHEDULE_ONLY, installmentRows = shiftedMonthlyRows)
    )
    assertEquals(RepaymentFrequency.MONTHLY, monthly.frequency.value)
    assertEquals(FieldOrigin.ESTIMATED, monthly.frequency.origin)

    val irregularRows = listOf(
      ExtractedInstallmentRow("i1", dueDateIso = "2026-10-01", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("i2", dueDateIso = "2026-10-08", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("i3", dueDateIso = "2026-10-29", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("i4", dueDateIso = "2026-11-10", dateConfidence = ExtractionConfidence.HIGH)
    )
    val irregular = FinancialReasoningEngine.enrichSinglePage(
      SingleScreenshotExtraction(0, ExtractedDocumentKind.SCHEDULE_ONLY, installmentRows = irregularRows)
    )
    assertEquals(RepaymentFrequency.IRREGULAR, irregular.frequency.value)

    // True semi-monthly (15th & 30th) is classified as TWICE_MONTHLY (never 14-day biweekly!)
    val semiMonthlyRows = listOf(
      ExtractedInstallmentRow("sm1", dueDateIso = "2026-04-15", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("sm2", dueDateIso = "2026-04-30", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("sm3", dueDateIso = "2026-05-15", dateConfidence = ExtractionConfidence.HIGH),
      ExtractedInstallmentRow("sm4", dueDateIso = "2026-05-30", dateConfidence = ExtractionConfidence.HIGH)
    )
    val semiMonthly = FinancialReasoningEngine.enrichSinglePage(
      SingleScreenshotExtraction(0, ExtractedDocumentKind.SCHEDULE_ONLY, installmentRows = semiMonthlyRows)
    )
    assertEquals(RepaymentFrequency.TWICE_MONTHLY, semiMonthly.frequency.value)
    assertEquals(FieldOrigin.ESTIMATED, semiMonthly.frequency.origin)
  }

  @Test
  fun estimatesMissingNextDueDateFromLastPaidDateAndWeeklyPattern() {
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SUMMARY_ONLY,
      outstandingBalanceCents = ExtractedField.extracted(15_000L, "Balance ₱150.00", 0),
      frequency = ExtractedField.extracted(RepaymentFrequency.WEEKLY, "Weekly", 0),
      lastPaidDateIso = ExtractedField.extracted("2026-11-09", "Last paid Nov 9, 2026", 0)
    )

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw)

    assertEquals("2026-11-16", enriched.nextDueDateIso.value)
    assertEquals(FieldOrigin.ESTIMATED, enriched.nextDueDateIso.origin)
    assertEquals(ExtractionConfidence.MEDIUM, enriched.nextDueDateIso.confidence)
    assertFalse(enriched.isNextDueDateExplicit)
    assertTrue(enriched.nextDueDateIso.verificationReason!!.contains("weekly", ignoreCase = true))
  }

  @Test
  fun zeroBalanceDoesNotAutoAssumePaidWhenNoPaidEvidenceAndDowngradesLowOcrQuality() {
    val raw = SingleScreenshotExtraction(
      screenshotIndex = 0,
      documentKind = ExtractedDocumentKind.SUMMARY_ONLY,
      outstandingBalanceCents = ExtractedField.extracted(0L, "Balance ₱0.00", 0),
      originalPrincipalCents = ExtractedField.extracted(100_000L, "Loan Amount ₱1,000.00", 0)
    )
    val lowQuality = OcrPageQuality(averageConfidence = 0.65f, lowConfidenceLineCount = 4)

    val enriched = FinancialReasoningEngine.enrichSinglePage(raw, lowQuality)

    // Never auto-assumes PAID just because balance read ₱0.00
    assertNull(enriched.loanStatus.value)
    assertEquals(ExtractionConfidence.LOW, enriched.outstandingBalanceCents.confidence)
    assertTrue(enriched.issues.any { it.title.contains("Zero balance") })
    // Low OCR quality downgrades extracted principal from HIGH to MEDIUM
    assertEquals(ExtractionConfidence.MEDIUM, enriched.originalPrincipalCents.confidence)
    assertTrue(enriched.originalPrincipalCents.verificationReason!!.contains("clarity", ignoreCase = true))
  }
}

