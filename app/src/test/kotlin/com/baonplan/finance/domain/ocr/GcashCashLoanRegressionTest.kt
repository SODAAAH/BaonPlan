package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GcashCashLoanRegressionTest {

  private val refDate = LocalDate.of(2026, 12, 10)

  @Test
  fun extractsGcashCashLoanScreenshotWithEmblemRefBookingMultiplierPartialScheduleAndExactMath() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1080,
      height = 2400,
      lines = listOf(
        // Status bar noise
        OcrLine("1:49", OcrBoundingBox(40, 12, 140, 44)),
        OcrLine("69%", OcrBoundingBox(960, 12, 1040, 44)),
        // Top tabs
        OcrLine("Schedule", OcrBoundingBox(60, 110, 260, 148)),
        OcrLine("Transactions", OcrBoundingBox(410, 110, 660, 148)),
        OcrLine("Documents", OcrBoundingBox(790, 110, 1010, 148)),
        // Header card with circular G emblem and bare reference ID
        OcrLine("G)) P1,000.00 GCash Cash Loan", OcrBoundingBox(70, 230, 720, 272)),
        OcrLine("#46008372", OcrBoundingBox(750, 230, 980, 272)),
        // Disbursement / booking details
        OcrLine("Sent to +639627880026", OcrBoundingBox(70, 340, 560, 376)),
        OcrLine("Oct 23, 2026", OcrBoundingBox(70, 392, 340, 428)),
        // Installment multiplier & Remaining balance
        OcrLine("Installment", OcrBoundingBox(70, 480, 310, 516)),
        OcrLine("₱51.78 x 24", OcrBoundingBox(730, 480, 990, 516)),
        OcrLine("Remaining", OcrBoundingBox(70, 540, 310, 576)),
        OcrLine("₱1,087.38", OcrBoundingBox(770, 540, 990, 576)),
        // Progress bar fraction
        OcrLine("Schedule", OcrBoundingBox(120, 680, 340, 722)),
        OcrLine("Paid: 3/24", OcrBoundingBox(40, 790, 240, 826)),
        // 3 paid rows + 3 visible rows without status text
        OcrLine("Nov 02, 2026", OcrBoundingBox(70, 890, 320, 926)),
        OcrLine("₱51.78", OcrBoundingBox(400, 890, 540, 926)),
        OcrLine("paid", OcrBoundingBox(800, 890, 900, 926)),

        OcrLine("Nov 09, 2026", OcrBoundingBox(70, 1010, 320, 1046)),
        OcrLine("₱51.78", OcrBoundingBox(400, 1010, 540, 1046)),
        OcrLine("paid", OcrBoundingBox(800, 1010, 900, 1046)),

        OcrLine("Nov 16, 2026", OcrBoundingBox(70, 1130, 320, 1166)),
        OcrLine("₱51.78", OcrBoundingBox(400, 1130, 540, 1166)),
        OcrLine("paid", OcrBoundingBox(800, 1130, 900, 1166)),

        OcrLine("Nov 23, 2026", OcrBoundingBox(70, 1250, 320, 1286)),
        OcrLine("₱51.78", OcrBoundingBox(400, 1250, 540, 1286)),

        OcrLine("Dec 01, 2026", OcrBoundingBox(70, 1370, 320, 1406)),
        OcrLine("₱51.78", OcrBoundingBox(400, 1370, 540, 1406)),

        OcrLine("Dec 07, 2026", OcrBoundingBox(70, 1490, 320, 1526)),
        OcrLine("₱51.78", OcrBoundingBox(400, 1490, 540, 1526))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, refDate)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )

    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    // 1. Provider (BillEase via Schedule|Transactions|Documents + Cash Loan #id header), clean title, ref ID
    assertEquals("BillEase", draft.lender.value)
    assertEquals(LoanProductClassification.FIXED_INSTALLMENT_LOAN, draft.productClassification.value)
    assertEquals("46008372", draft.loanReferenceId.value)
    assertFalse("Loan name must not include circular G)) emblem", draft.loanName.value!!.contains("G))"))
    assertTrue(draft.loanName.value.contains("GCash Cash Loan"))

    // 2. Principal vs Remaining Balance vs Installment Multiplier vs Total Repayment
    assertEquals(100_000L, draft.originalPrincipalCents.value)
    assertEquals(108_738L, draft.outstandingBalanceCents.value)
    assertEquals(5_178L, draft.installmentAmountCents.value)
    assertEquals(24, draft.originalInstallmentCount.value)
    assertEquals(3, draft.paidInstallmentsCount.value)
    // Arithmetic 24 - 3 = 21 remaining installments (NOT 3 visible rows!)
    assertEquals(21, draft.remainingInstallmentsCount.value)
    assertEquals(FieldOrigin.CALCULATED, draft.remainingInstallmentsCount.origin)
    // 21 * ₱51.78 = ₱1,087.38 (NOT ₱155.34!) — directly extracted from "Remaining | ₱1,087.38" and verified
    assertEquals(108_738L, draft.remainingInstallmentTotalCents.value)
    assertEquals(FieldOrigin.EXTRACTED, draft.remainingInstallmentTotalCents.origin)
    // 24 * ₱51.78 = ₱1,242.72 (marked ESTIMATED because only 6 of 24 rows are visible)
    assertEquals(124_272L, draft.totalRepaymentCents.value)
    assertEquals(FieldOrigin.ESTIMATED, draft.totalRepaymentCents.origin)
    // 3 paid rows * ₱51.78 = ₱155.34
    assertEquals(15_534L, draft.calculatedPaidScheduleSumCents.value)
    assertEquals("2026-10-23", draft.loanBookingDateIso.value)

    // 3. Partial schedule detection & status integrity (never auto-marking unlabeled dates as paid/overdue)
    assertTrue(draft.isSchedulePartial)
    assertEquals(6, draft.installmentRows.size)
    assertEquals(3, draft.installmentRows.count { it.status == ExtractedInstallmentStatus.PAID })
    assertEquals(3, draft.installmentRows.count { it.status == ExtractedInstallmentStatus.UNKNOWN })
    // Next due date is the first non-paid row (Nov 23, 2026)
    assertEquals("2026-11-23", draft.nextDueDateIso.value)
    assertEquals("2026-11-16", draft.lastPaidDateIso.value)

    // 4. Validator recognizes partial schedule and does NOT emit false row-count mismatch warnings
    assertFalse(draft.validationIssues.any { it.fieldKey == "remainingRowMismatch" })
    assertFalse(draft.validationIssues.any { it.fieldKey == "scheduleSumVsBalance" })
    assertTrue(draft.validationIssues.any { it.fieldKey == "partialScheduleNotice" })
  }

  @Test
  fun irregularDatesInGcashLikeScheduleArePreservedAsIrregularWithoutForcingWeekly() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("P1,000.00 GCash Cash Loan #46008372"),
        OcrLine("Installment | ₱51.78 x 24"),
        OcrLine("Remaining | ₱1,087.38"),
        OcrLine("Paid: 3/24"),
        OcrLine("Nov 16, 2026 | ₱51.78 | paid"),
        OcrLine("Nov 23, 2026 | ₱51.78"),
        OcrLine("Dec 1, 2026 | ₱51.78"),
        OcrLine("Dec 7, 2026 | ₱51.78"),
        OcrLine("Dec 14, 2026 | ₱51.78")
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    // Intervals: 7d, 8d, 6d, 7d -> 6..8d jitter is weekly, OR if wider irregular gap e.g. Dec 28:
    val irregularGapPage = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("P1,000.00 GCash Cash Loan #46008372"),
        OcrLine("Installment | ₱51.78 x 24"),
        OcrLine("Remaining | ₱1,087.38"),
        OcrLine("Paid: 3/24"),
        OcrLine("Nov 16, 2026 | ₱51.78 | paid"),
        OcrLine("Nov 23, 2026 | ₱51.78"),
        OcrLine("Dec 1, 2026 | ₱51.78"),
        OcrLine("Dec 19, 2026 | ₱51.78"),
        OcrLine("Jan 11, 2027 | ₱51.78")
      )
    )
    val irregularEx = DebtOcrExtractor.extract(irregularGapPage, refDate)
    assertEquals(RepaymentFrequency.IRREGULAR, irregularEx.frequency.value)
    assertEquals(
      listOf("2026-11-16", "2026-11-23", "2026-12-01", "2026-12-19", "2027-01-11"),
      irregularEx.installmentRows.map { it.dueDateIso }
    )
    assertEquals(ExtractedInstallmentStatus.UNKNOWN, irregularEx.installmentRows[1].status)
    assertEquals(21, ex.remainingInstallmentsCount.value)
  }

  @Test
  fun nativeGcashGloanStillResolvesToGcashAndFuseLending() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("GLoan Dashboard"),
        OcrLine("Fuse Lending Inc."),
        OcrLine("Loan Account No: GL-998811"),
        OcrLine("Loan Amount: ₱5,000.00"),
        OcrLine("Remaining Balance: ₱3,000.00"),
        OcrLine("Next Due Date: Dec 10, 2026")
      )
    )
    val outcome = MultiScreenshotDebtMerger.merge(listOf(DebtOcrExtractor.extract(page, refDate)))
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals("GCash (GLoan)", draft.lender.value)
    assertEquals("Fuse Lending Inc.", draft.lendingCompany.value)
  }
}
