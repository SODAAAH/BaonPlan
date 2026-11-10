package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MultiScreenshotDebtMergerTest {

  private val refDate = LocalDate.of(2026, 11, 10)

  @Test
  fun mergesComplementarySummaryAndSchedulePagesWithoutDuplicatingRows() {
    val pageSummary = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Shopee SPayLater"),
        OcrLine("Order #ORD-8821"),
        OcrLine("Loan Name: Textbook Bundle"),
        OcrLine("Original Loan Amount: ₱2,000.00"),
        OcrLine("Remaining Balance: ₱1,500.00"),
        OcrLine("Next Payment Due: Nov 16, 2026")
      )
    )
    val pageSchedule = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("SPayLater Payment Schedule"),
        OcrLine("Order #ORD-8821"),
        OcrLine("#1 | Oct 16, 2026 | ₱500.00 | Paid"),
        OcrLine("#2 | Nov 16, 2026 | ₱500.00 | Upcoming"),
        OcrLine("#3 | Dec 16, 2026 | ₱500.00 | Upcoming"),
        OcrLine("#4 | Jan 16, 2027 | ₱500.00 | Upcoming")
      )
    )

    val ex1 = DebtOcrExtractor.extract(pageSummary, refDate)
    val ex2 = DebtOcrExtractor.extract(pageSchedule, refDate)
    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(ex1, ex2),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )

    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(listOf(0, 1), draft.sourceScreenshotIndices)
    assertEquals("Textbook Bundle", draft.loanName.value)
    assertEquals("ORD-8821", draft.loanReferenceId.value)
    assertEquals(200_000L, draft.originalPrincipalCents.value)
    assertEquals(150_000L, draft.outstandingBalanceCents.value)
    assertEquals(4, draft.installmentRows.size)
    assertEquals(50_000L, draft.calculatedPaidScheduleSumCents.value)
    assertEquals(FieldOrigin.CALCULATED, draft.calculatedPaidScheduleSumCents.origin)
    assertEquals(150_000L, draft.calculatedUnpaidScheduleSumCents.value)
    assertEquals(FieldOrigin.CALCULATED, draft.calculatedUnpaidScheduleSumCents.origin)
    assertEquals(1, draft.paidInstallmentsCount.value)
    assertEquals(3, draft.remainingInstallmentsCount.value)
  }

  @Test
  fun deduplicatesOverlappingScheduleDatesAcrossScrollScreenshots() {
    val pageTop = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase Schedule"),
        OcrLine("#1 | Oct 15, 2026 | ₱600.00 | Paid"),
        OcrLine("#2 | Nov 15, 2026 | ₱600.00 | Upcoming"),
        OcrLine("#3 | Dec 15, 2026 | ₱600.00 | Upcoming")
      )
    )
    val pageBottom = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("BillEase Schedule"),
        OcrLine("#3 | Dec 15, 2026 | ₱600.00 | Upcoming"),
        OcrLine("#4 | Jan 15, 2027 | ₱600.00 | Upcoming")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      listOf(
        DebtOcrExtractor.extract(pageTop, refDate),
        DebtOcrExtractor.extract(pageBottom, refDate)
      )
    )

    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(
      listOf("2026-10-15", "2026-11-15", "2026-12-15", "2027-01-15"),
      draft.installmentRows.map { it.dueDateIso }
    )
  }

  @Test
  fun keepsTwoDistinctLoansFromSameLenderStrictlySeparate() {
    val loanA = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Shopee SPayLater"),
        OcrLine("Order #SP-112233"),
        OcrLine("Remaining Balance: ₱1,200.00"),
        OcrLine("Monthly Installment: ₱400.00"),
        OcrLine("Next Payment Due: Nov 15, 2026")
      )
    )
    val loanB = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("Shopee SPayLater"),
        OcrLine("Order #SP-998877"),
        OcrLine("Remaining Balance: ₱2,400.00"),
        OcrLine("Monthly Installment: ₱600.00"),
        OcrLine("Next Payment Due: Nov 25, 2026")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      listOf(
        DebtOcrExtractor.extract(loanA, refDate),
        DebtOcrExtractor.extract(loanB, refDate)
      )
    )

    assertTrue(outcome is ScanAnalysisOutcome.MultipleLoans)
    val multi = outcome as ScanAnalysisOutcome.MultipleLoans
    assertEquals(2, multi.drafts.size)
    assertEquals("SP-112233", multi.drafts[0].loanReferenceId.value)
    assertEquals(120_000L, multi.drafts[0].outstandingBalanceCents.value)
    assertEquals("SP-998877", multi.drafts[1].loanReferenceId.value)
    assertEquals(240_000L, multi.drafts[1].outstandingBalanceCents.value)
  }

  @Test
  fun flagsCrossScreenshotConflictWithoutSilentOverwriteWhenSameOrderSharesId() {
    val page1 = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase"),
        OcrLine("Contract ID: BE-7711"),
        OcrLine("Remaining Balance: ₱1,500.00")
      )
    )
    val page2 = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("BillEase Schedule"),
        OcrLine("Contract ID: BE-7711"),
        OcrLine("Remaining Balance: ₱1,200.00"),
        OcrLine("#1 | Nov 20, 2026 | ₱600.00 | Upcoming"),
        OcrLine("#2 | Dec 20, 2026 | ₱600.00 | Upcoming")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      listOf(
        DebtOcrExtractor.extract(page1, refDate),
        DebtOcrExtractor.extract(page2, refDate)
      )
    )

    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(ExtractionConfidence.LOW, draft.outstandingBalanceCents.confidence)
    assertTrue(draft.outstandingBalanceCents.hasConflict)
    assertTrue(draft.outstandingBalanceCents.alternatives.isNotEmpty())
    assertTrue(draft.validationIssues.any { it.title.contains("differs across screenshots") })
  }

  @Test
  fun linksThreePartialScreenshotsIntoSingleCoherentDebtRecord() {
    // Screenshot 1: Origination terms (lender, loan amount, installment amount)
    val s1 = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("GCash GLoan"),
        OcrLine("Loan Name: Study Allowance Loan"),
        OcrLine("Loan Amount: ₱2,400.00"),
        OcrLine("Monthly Installment: ₱400.00"),
        OcrLine("Total Installments: 6")
      )
    )
    // Screenshot 2: Payment history (paid count & paid rows)
    val s2 = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("GCash GLoan Payment History"),
        OcrLine("#1 | Sep 16, 2026 | ₱400.00 | Paid"),
        OcrLine("#2 | Oct 16, 2026 | ₱400.00 | Paid")
      )
    )
    // Screenshot 3: Upcoming payment schedule & balance
    val s3 = OcrDocumentPage(
      screenshotIndex = 2,
      lines = listOf(
        OcrLine("GCash GLoan"),
        OcrLine("Remaining Balance: ₱1,600.00"),
        OcrLine("#3 | Nov 16, 2026 | ₱400.00 | Upcoming"),
        OcrLine("#4 | Dec 16, 2026 | ₱400.00 | Upcoming"),
        OcrLine("#5 | Jan 16, 2027 | ₱400.00 | Upcoming"),
        OcrLine("#6 | Feb 16, 2027 | ₱400.00 | Upcoming")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      listOf(
        DebtOcrExtractor.extract(s1, refDate),
        DebtOcrExtractor.extract(s2, refDate),
        DebtOcrExtractor.extract(s3, refDate)
      )
    )

    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(listOf(0, 1, 2), draft.sourceScreenshotIndices)
    assertEquals(240_000L, draft.originalPrincipalCents.value)
    assertEquals(160_000L, draft.outstandingBalanceCents.value)
    assertEquals(40_000L, draft.installmentAmountCents.value)
    assertEquals(6, draft.originalInstallmentCount.value)
    assertEquals(2, draft.paidInstallmentsCount.value)
    assertEquals(4, draft.remainingInstallmentsCount.value)
    assertEquals(6, draft.installmentRows.size)
    assertEquals("2026-11-16", draft.nextDueDateIso.value)
    assertEquals("2026-10-16", draft.lastPaidDateIso.value)
    assertEquals(RepaymentFrequency.MONTHLY, draft.frequency.value)
  }

  @Test
  fun prefersNewerSnapshotBalanceAndPreservesOlderValueInAlternatives() {
    val olderPage = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase"),
        OcrLine("Contract ID: BE-5500"),
        OcrLine("As of: Oct 10, 2026"),
        OcrLine("Remaining Balance: ₱1,800.00"),
        OcrLine("Paid Installments: 1")
      )
    )
    val newerPage = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("BillEase"),
        OcrLine("Contract ID: BE-5500"),
        OcrLine("As of: Nov 10, 2026"),
        OcrLine("Remaining Balance: ₱1,200.00"),
        OcrLine("Paid Installments: 2")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      listOf(
        DebtOcrExtractor.extract(olderPage, refDate),
        DebtOcrExtractor.extract(newerPage, refDate)
      )
    )

    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(120_000L, draft.outstandingBalanceCents.value)
    assertTrue(!draft.outstandingBalanceCents.hasConflict)
    assertTrue(draft.outstandingBalanceCents.alternatives.any { it.value == 180_000L })
    assertEquals(2, draft.paidInstallmentsCount.value)
  }

  @Test
  fun supportsUserConfirmedCustomGroupingOverrides() {
    val page0 = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Shopee SPayLater"),
          OcrLine("Order #SP-100"),
          OcrLine("Original Loan Amount: ₱2,000.00")
        )
      ),
      refDate
    )
    val page1 = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 1,
        lines = listOf(
          OcrLine("Shopee SPayLater"),
          OcrLine("Order #SP-200"),
          OcrLine("Remaining Balance: ₱1,500.00")
        )
      ),
      refDate
    )

    // Default auto-detect keeps different order IDs separate
    val autoOutcome = MultiScreenshotDebtMerger.merge(listOf(page0, page1))
    assertTrue(autoOutcome is ScanAnalysisOutcome.MultipleLoans)

    // User overrides to combine both screenshots into one loan
    val combined = MultiScreenshotDebtMerger.mergeWithCustomGroups(
      pages = listOf(page0, page1),
      groupedScreenshotIndices = listOf(listOf(0, 1))
    )
    assertTrue(combined is ScanAnalysisOutcome.SingleLoan)
    val combinedDraft = (combined as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(listOf(0, 1), combinedDraft.sourceScreenshotIndices)
    assertEquals(200_000L, combinedDraft.originalPrincipalCents.value)
    assertEquals(150_000L, combinedDraft.outstandingBalanceCents.value)
  }

  @Test
  fun returnsFailedOutcomeWhenImagesContainOnlyUnstructuredNoise() {
    val noise = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("12:41 PM 5G"),
        OcrLine("Settings Help Privacy Terms")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      listOf(DebtOcrExtractor.extract(noise, refDate))
    )

    assertTrue(outcome is ScanAnalysisOutcome.Failed)
  }
}
