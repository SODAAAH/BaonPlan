package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Calibrated end-to-end extraction and schedule projection tests modeled after the 6 annotated
 * Philippine lending app screenshots in `training assets/` and user-reported edge cases.
 */
class TrainingAssetsExtractionTest {

  private val todayNov10 = LocalDate.of(2026, 11, 10)

  @Test
  fun screenshot155303_billEaseGcashCashLoan_isHighConfidenceAndProjectsApr12Payoff() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("1:49", OcrBoundingBox(55, 28, 130, 62)),
        OcrLine("69%", OcrBoundingBox(1080, 28, 1170, 62)),
        OcrLine("Schedule", OcrBoundingBox(95, 195, 295, 240)),
        OcrLine("Transactions", OcrBoundingBox(480, 195, 760, 240)),
        OcrLine("Documents", OcrBoundingBox(895, 195, 1135, 240)),
        OcrLine("G))", OcrBoundingBox(85, 360, 155, 425)),
        OcrLine("P1,000.00 GCash Cash Loan", OcrBoundingBox(180, 368, 760, 415)),
        OcrLine("#46008372", OcrBoundingBox(835, 368, 1085, 415)),
        OcrLine("Sent to +639170000001", OcrBoundingBox(85, 480, 560, 520)),
        OcrLine("Oct 23, 2026", OcrBoundingBox(85, 535, 330, 575)),
        OcrLine("Installment", OcrBoundingBox(85, 650, 320, 690)),
        OcrLine("₱51.78 x 24", OcrBoundingBox(875, 650, 1135, 690)),
        OcrLine("Remaining", OcrBoundingBox(85, 715, 320, 755)),
        OcrLine("₱1,087.38", OcrBoundingBox(915, 715, 1135, 755)),
        OcrLine("Schedule", OcrBoundingBox(145, 865, 370, 915)),
        OcrLine("Paid: 3/24", OcrBoundingBox(50, 990, 260, 1030)),
        OcrLine("Nov 02, 2026", OcrBoundingBox(95, 1125, 360, 1165)),
        OcrLine("₱51.78", OcrBoundingBox(450, 1125, 610, 1165)),
        OcrLine("paid", OcrBoundingBox(930, 1125, 1045, 1165)),
        OcrLine("Nov 09, 2026", OcrBoundingBox(95, 1280, 360, 1320)),
        OcrLine("₱51.78", OcrBoundingBox(450, 1280, 610, 1320)),
        OcrLine("paid", OcrBoundingBox(930, 1280, 1045, 1320)),
        OcrLine("Nov 16, 2026", OcrBoundingBox(95, 1435, 360, 1475)),
        OcrLine("₱51.78", OcrBoundingBox(450, 1435, 610, 1475)),
        OcrLine("paid", OcrBoundingBox(930, 1435, 1045, 1475)),
        OcrLine("Nov 23, 2026", OcrBoundingBox(95, 1590, 360, 1630)),
        OcrLine("₱51.78", OcrBoundingBox(450, 1590, 610, 1630)),
        OcrLine("Dec 01, 2026", OcrBoundingBox(95, 1745, 360, 1785)),
        OcrLine("₱51.78", OcrBoundingBox(450, 1745, 610, 1785)),
        OcrLine("Dec 07, 2026", OcrBoundingBox(95, 1900, 360, 1940)),
        OcrLine("₱51.78", OcrBoundingBox(450, 1900, 610, 1940))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("BillEase", draft.lender.value)
    assertEquals("BillEase", draft.applicationProvider.value)
    assertEquals("46008372", draft.loanReferenceId.value)
    assertFalse(draft.loanName.value!!.contains("G))"))
    assertEquals(ExtractionConfidence.HIGH, draft.loanName.confidence)
    assertEquals(100_000L, draft.originalPrincipalCents.value)
    assertEquals(108_738L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(5_178L, draft.installmentAmountCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.installmentAmountCents.confidence)
    assertEquals(24, draft.originalInstallmentCount.value)
    assertEquals(3, draft.paidInstallmentsCount.value)
    assertEquals(21, draft.remainingInstallmentsCount.value)
    assertEquals(ExtractionConfidence.HIGH, draft.remainingInstallmentsCount.confidence)
    assertEquals(RepaymentFrequency.WEEKLY, draft.frequency.value)
    assertEquals(ExtractionConfidence.HIGH, draft.frequency.confidence)
    assertEquals("2026-11-23", draft.nextDueDateIso.value)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertEquals("In 13 days", ManilaTime.formatDaysUntilDue(draft.nextDueDateIso.value, todayNov10))
    assertEquals("2027-04-12", draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot155310_billEaseMayaPaidOnlyScrolled_recoversNov23AndProjectsMar15() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("Schedule", OcrBoundingBox(95, 195, 295, 240)),
        OcrLine("Transactions", OcrBoundingBox(480, 195, 760, 240)),
        OcrLine("Documents", OcrBoundingBox(895, 195, 1135, 240)),
        OcrLine("₱500.00 Maya Cash Loan", OcrBoundingBox(180, 368, 760, 415)),
        OcrLine("#44368828", OcrBoundingBox(835, 368, 1085, 415)),
        OcrLine("Sent to +639170000001", OcrBoundingBox(85, 480, 560, 520)),
        OcrLine("Sep 24, 2026", OcrBoundingBox(85, 535, 330, 575)),
        OcrLine("Installment", OcrBoundingBox(85, 650, 320, 690)),
        OcrLine("₱26.15 x 24", OcrBoundingBox(875, 650, 1135, 690)),
        OcrLine("Remaining", OcrBoundingBox(85, 715, 320, 755)),
        OcrLine("₱444.55", OcrBoundingBox(935, 715, 1135, 755)),
        OcrLine("Paid: 7/24", OcrBoundingBox(50, 990, 260, 1030)),
        // Exact visible 6 paid rows (#1..#6); #7 (Nov 16) is scrolled off-screen
        OcrLine("Oct 05, 2026", OcrBoundingBox(95, 1125, 360, 1165)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1125, 610, 1165)),
        OcrLine("paid", OcrBoundingBox(930, 1125, 1045, 1165)),
        OcrLine("Oct 12, 2026", OcrBoundingBox(95, 1280, 360, 1320)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1280, 610, 1320)),
        OcrLine("paid", OcrBoundingBox(930, 1280, 1045, 1320)),
        OcrLine("Oct 19, 2026", OcrBoundingBox(95, 1435, 360, 1475)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1435, 610, 1475)),
        OcrLine("paid", OcrBoundingBox(930, 1435, 1045, 1475)),
        OcrLine("Oct 26, 2026", OcrBoundingBox(95, 1590, 360, 1630)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1590, 610, 1630)),
        OcrLine("paid", OcrBoundingBox(930, 1590, 1045, 1630)),
        OcrLine("Nov 02, 2026", OcrBoundingBox(95, 1745, 360, 1785)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1745, 610, 1785)),
        OcrLine("paid", OcrBoundingBox(930, 1745, 1045, 1785)),
        OcrLine("Nov 09, 2026", OcrBoundingBox(95, 1900, 360, 1940)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1900, 610, 1940)),
        OcrLine("paid", OcrBoundingBox(930, 1900, 1045, 1940))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("BillEase", draft.lender.value)
    assertEquals(ExtractionConfidence.HIGH, draft.lender.confidence)
    assertEquals("44368828", draft.loanReferenceId.value)
    assertEquals("Maya Cash Loan #44368828", draft.loanName.value)
    assertEquals(ExtractionConfidence.HIGH, draft.loanName.confidence)
    assertEquals(50_000L, draft.originalPrincipalCents.value)
    assertEquals(44_455L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(2_615L, draft.installmentAmountCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.installmentAmountCents.confidence)
    assertEquals(24, draft.originalInstallmentCount.value)
    assertEquals(7, draft.paidInstallmentsCount.value)
    assertEquals(17, draft.remainingInstallmentsCount.value)
    assertEquals(ExtractionConfidence.HIGH, draft.remainingInstallmentsCount.confidence)
    assertEquals(RepaymentFrequency.WEEKLY, draft.frequency.value)
    assertEquals(ExtractionConfidence.HIGH, draft.frequency.confidence)
    assertEquals("2026-11-23", draft.nextDueDateIso.value)
    assertEquals(ExtractionConfidence.HIGH, draft.nextDueDateIso.confidence)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertEquals("2027-03-15", draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot155310_bottomScrolledOct26ToNov16_recoversNov23AndProjectsMar15() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("Schedule", OcrBoundingBox(95, 195, 295, 240)),
        OcrLine("Transactions", OcrBoundingBox(480, 195, 760, 240)),
        OcrLine("Documents", OcrBoundingBox(895, 195, 1135, 240)),
        OcrLine("₱500.00 Maya Cash Loan", OcrBoundingBox(180, 368, 760, 415)),
        OcrLine("#44368828", OcrBoundingBox(835, 368, 1085, 415)),
        OcrLine("Sent to +639170000001", OcrBoundingBox(85, 480, 560, 520)),
        OcrLine("Sep 24, 2026", OcrBoundingBox(85, 535, 330, 575)),
        OcrLine("Installment", OcrBoundingBox(85, 650, 320, 690)),
        OcrLine("₱26.15 x 24", OcrBoundingBox(875, 650, 1135, 690)),
        OcrLine("Remaining", OcrBoundingBox(85, 715, 320, 755)),
        OcrLine("₱444.55", OcrBoundingBox(935, 715, 1135, 755)),
        OcrLine("Paid: 7/24", OcrBoundingBox(50, 990, 260, 1030)),
        // Scrolled to bottom of paid block: #4 (Oct 26) .. #7 (Nov 16)
        OcrLine("Oct 26, 2026", OcrBoundingBox(95, 1125, 360, 1165)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1125, 610, 1165)),
        OcrLine("paid", OcrBoundingBox(930, 1125, 1045, 1165)),
        OcrLine("Nov 02, 2026", OcrBoundingBox(95, 1280, 360, 1320)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1280, 610, 1320)),
        OcrLine("paid", OcrBoundingBox(930, 1280, 1045, 1320)),
        OcrLine("Nov 09, 2026", OcrBoundingBox(95, 1435, 360, 1475)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1435, 610, 1475)),
        OcrLine("paid", OcrBoundingBox(930, 1435, 1045, 1475)),
        OcrLine("Nov 16, 2026", OcrBoundingBox(95, 1590, 360, 1630)),
        OcrLine("₱26.15", OcrBoundingBox(450, 1590, 610, 1630)),
        OcrLine("paid", OcrBoundingBox(930, 1590, 1045, 1630))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("BillEase", draft.lender.value)
    assertEquals("Maya Cash Loan #44368828", draft.loanName.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(RepaymentFrequency.WEEKLY, draft.frequency.value)
    assertEquals("2026-11-23", draft.nextDueDateIso.value)
    assertEquals(ExtractionConfidence.HIGH, draft.nextDueDateIso.confidence)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertEquals("2027-03-15", draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot155316_billEaseBiweeklyBonifacioDayShift_recoversDec01AndSnapsBackToDec28() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("Schedule", OcrBoundingBox(95, 195, 295, 240)),
        OcrLine("Transactions", OcrBoundingBox(480, 195, 760, 240)),
        OcrLine("Documents", OcrBoundingBox(895, 195, 1135, 240)),
        OcrLine("₱1,000.00 GCash E-Wallet", OcrBoundingBox(180, 368, 760, 415)),
        OcrLine("#40423459", OcrBoundingBox(835, 368, 1085, 415)),
        OcrLine("Sent to +639170000001", OcrBoundingBox(85, 480, 560, 520)),
        OcrLine("Jul 15, 2026", OcrBoundingBox(85, 535, 330, 575)),
        OcrLine("Installment", OcrBoundingBox(85, 650, 320, 690)),
        OcrLine("₱111.69 x 12", OcrBoundingBox(875, 650, 1135, 690)),
        OcrLine("Remaining", OcrBoundingBox(85, 715, 320, 755)),
        OcrLine("₱335.07", OcrBoundingBox(935, 715, 1135, 755)),
        OcrLine("Paid: 9/12", OcrBoundingBox(50, 990, 260, 1030)),
        // Exact visible 6 paid biweekly rows (#1..#6); #7..#9 (Oct 19, Nov 02, Nov 16) scrolled off
        OcrLine("Jul 27, 2026", OcrBoundingBox(95, 1125, 360, 1165)),
        OcrLine("₱114.38", OcrBoundingBox(450, 1125, 610, 1165)),
        OcrLine("paid", OcrBoundingBox(930, 1125, 1045, 1165)),
        OcrLine("Aug 10, 2026", OcrBoundingBox(95, 1280, 360, 1320)),
        OcrLine("₱113.67", OcrBoundingBox(450, 1280, 610, 1320)),
        OcrLine("paid", OcrBoundingBox(930, 1280, 1045, 1320)),
        OcrLine("Aug 24, 2026", OcrBoundingBox(95, 1435, 360, 1475)),
        OcrLine("₱111.69", OcrBoundingBox(450, 1435, 610, 1475)),
        OcrLine("paid", OcrBoundingBox(930, 1435, 1045, 1475)),
        OcrLine("Sep 07, 2026", OcrBoundingBox(95, 1590, 360, 1630)),
        OcrLine("₱111.69", OcrBoundingBox(450, 1590, 610, 1630)),
        OcrLine("paid", OcrBoundingBox(930, 1590, 1045, 1630)),
        OcrLine("Sep 21, 2026", OcrBoundingBox(95, 1745, 360, 1785)),
        OcrLine("₱111.69", OcrBoundingBox(450, 1745, 610, 1785)),
        OcrLine("paid", OcrBoundingBox(930, 1745, 1045, 1785)),
        OcrLine("Oct 05, 2026", OcrBoundingBox(95, 1900, 360, 1940)),
        OcrLine("₱111.69", OcrBoundingBox(450, 1900, 610, 1940)),
        OcrLine("paid", OcrBoundingBox(930, 1900, 1045, 1940))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("BillEase", draft.lender.value)
    assertEquals(ExtractionConfidence.HIGH, draft.lender.confidence)
    assertEquals("40423459", draft.loanReferenceId.value)
    assertEquals("GCash E-Wallet #40423459", draft.loanName.value)
    assertEquals(33_507L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(11_169L, draft.installmentAmountCents.value)
    assertEquals(3, draft.remainingInstallmentsCount.value)
    assertEquals(RepaymentFrequency.EVERY_14_DAYS, draft.frequency.value)
    // Nov 16 + 14d = Nov 30 (Bonifacio Day regular holiday) -> shifts to Dec 01 banking day
    assertEquals("2026-12-01", draft.nextDueDateIso.value)
    assertEquals(ExtractionConfidence.HIGH, draft.nextDueDateIso.confidence)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    // Snaps back to Monday anchor Nov 30 -> #11 Dec 14 -> #12 Dec 28, 2026
    assertEquals("2026-12-28", draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot155912_shopeeSpayLaterHome_ignoresPromoAndExtractsUpcomingBillAndCreditLimit() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("SPayLater", OcrBoundingBox(440, 150, 780, 210)),
        OcrLine("Available Credit", OcrBoundingBox(95, 320, 420, 360)),
        OcrLine("₱3,021.34", OcrBoundingBox(95, 375, 470, 450)),
        OcrLine("Total Credit 3,250 >", OcrBoundingBox(95, 510, 560, 550)),
        OcrLine("Next Bill to pay", OcrBoundingBox(85, 760, 360, 805)),
        OcrLine("₱229.17", OcrBoundingBox(85, 825, 320, 885)),
        OcrLine("Pay Early", OcrBoundingBox(875, 785, 1120, 860)),
        OcrLine("Due date 15 Dec 2026", OcrBoundingBox(85, 900, 450, 940)),
        OcrLine("My Bill >", OcrBoundingBox(200, 995, 420, 1040)),
        OcrLine("Transaction >", OcrBoundingBox(730, 995, 1020, 1040)),
        // Bottom SLoan / coins promo banners + SEC fine print must not confuse SPayLater or amounts
        OcrLine("SLoan", OcrBoundingBox(100, 1300, 240, 1340)),
        OcrLine("SLoan Up to ₱100k", OcrBoundingBox(310, 1890, 750, 1940)),
        OcrLine("SPayLater SURE WIN KADA ORDER", OcrBoundingBox(260, 2240, 960, 2290)),
        OcrLine("GET UP TO 11,000 COINS", OcrBoundingBox(240, 2320, 980, 2370)),
        OcrLine("NOV 9 - 30", OcrBoundingBox(480, 2390, 720, 2430)),
        OcrLine("Monee (Credit) Finance Philippines, Inc. doing business under SPayLater and SLoan", OcrBoundingBox(360, 2460, 1120, 2500))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("Shopee (SPayLater)", draft.lender.value)
    assertEquals(ExtractionConfidence.HIGH, draft.lender.confidence)
    assertEquals("SPayLater", draft.loanName.value)
    assertEquals(ExtractionConfidence.HIGH, draft.loanName.confidence)
    assertEquals("Monee (Credit) Finance Philippines, Inc.", draft.lendingCompany.value)
    assertEquals(LoanProductClassification.REVOLVING_CREDIT, draft.productClassification.value)
    assertEquals(22_917L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(22_917L, draft.amountCurrentlyDueCents.value)
    assertEquals(325_000L, draft.creditLimitCents.value)
    assertEquals(302_134L, draft.availableCreditCents.value)
    assertEquals("2026-12-15", draft.nextDueDateIso.value)
    assertEquals(ExtractionConfidence.HIGH, draft.nextDueDateIso.confidence)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertNull(draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot155912_withRecentTransactionsSection_staysRevolvingWithSpayLaterNameAnd22917Balance() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("SPayLater", OcrBoundingBox(440, 150, 780, 210)),
        OcrLine("Available Credit", OcrBoundingBox(95, 320, 420, 360)),
        OcrLine("₱3,021.34", OcrBoundingBox(95, 375, 470, 450)),
        OcrLine("Total Credit 3,250 >", OcrBoundingBox(95, 510, 560, 550)),
        OcrLine("Next Bill to pay", OcrBoundingBox(85, 760, 360, 805)),
        OcrLine("₱229.17", OcrBoundingBox(85, 825, 320, 885)),
        OcrLine("Pay Early", OcrBoundingBox(875, 785, 1120, 860)),
        OcrLine("Due date 15 Dec 2026", OcrBoundingBox(85, 900, 450, 940)),
        OcrLine("Recent Transactions", OcrBoundingBox(85, 1120, 520, 1165)),
        OcrLine("SPayLater Order #8812", OcrBoundingBox(85, 1200, 560, 1245))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("Shopee (SPayLater)", draft.lender.value)
    assertEquals(LoanProductClassification.REVOLVING_CREDIT, draft.productClassification.value)
    assertEquals("SPayLater", draft.loanName.value)
    assertNull(draft.loanReferenceId.value)
    assertEquals(22_917L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals("2026-12-15", draft.nextDueDateIso.value)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot155934_zeroBrandMyBills_infersSpayLaterWithoutMisreadingDueDateAsPartialSchedule() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("My Bills", OcrBoundingBox(470, 150, 750, 210)),
        OcrLine("Unpaid", OcrBoundingBox(210, 290, 410, 335)),
        OcrLine("Paid", OcrBoundingBox(830, 290, 970, 335)),
        OcrLine("Upcoming Bill", OcrBoundingBox(85, 430, 360, 475)),
        OcrLine("Nov", OcrBoundingBox(85, 535, 210, 585)),
        OcrLine("Unpaid", OcrBoundingBox(85, 600, 220, 640)),
        OcrLine("₱235.63 >", OcrBoundingBox(885, 585, 1135, 635)),
        OcrLine("Due Date 15 Dec 2026", OcrBoundingBox(85, 655, 495, 695))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("Shopee (SPayLater)", draft.lender.value)
    assertEquals(ExtractionConfidence.HIGH, draft.lender.confidence)
    assertEquals("SPayLater", draft.loanName.value)
    assertEquals(ExtractionConfidence.HIGH, draft.loanName.confidence)
    assertEquals(LoanProductClassification.REVOLVING_CREDIT, draft.productClassification.value)
    assertEquals(23_563L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals("2026-12-15", draft.nextDueDateIso.value)
    assertEquals(ExtractionConfidence.HIGH, draft.nextDueDateIso.confidence)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertTrue("Due Date line must not become a fake schedule row", draft.installmentRows.isEmpty())
    assertNull(draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun screenshot162444_billEaseZeroPaid24Installments_projectsMay03Payoff() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("Schedule", OcrBoundingBox(95, 195, 295, 240)),
        OcrLine("Transactions", OcrBoundingBox(480, 195, 760, 240)),
        OcrLine("Documents", OcrBoundingBox(895, 195, 1135, 240)),
        OcrLine("P1,500.00 Maya Cash Loan", OcrBoundingBox(180, 368, 760, 415)),
        OcrLine("#46976976", OcrBoundingBox(835, 368, 1085, 415)),
        OcrLine("Sent to +639170000001", OcrBoundingBox(85, 480, 560, 520)),
        OcrLine("Nov 10, 2026", OcrBoundingBox(85, 535, 330, 575)),
        OcrLine("Installment", OcrBoundingBox(85, 650, 320, 690)),
        OcrLine("₱77.42 x 24", OcrBoundingBox(875, 650, 1135, 690)),
        OcrLine("Remaining", OcrBoundingBox(85, 715, 320, 755)),
        OcrLine("₱1,858.08", OcrBoundingBox(905, 715, 1135, 755)),
        OcrLine("Paid: 0/24", OcrBoundingBox(50, 990, 260, 1030)),
        OcrLine("Nov 23, 2026", OcrBoundingBox(95, 1125, 360, 1165)),
        OcrLine("₱77.42", OcrBoundingBox(450, 1125, 610, 1165)),
        OcrLine("Dec 01, 2026", OcrBoundingBox(95, 1280, 360, 1320)),
        OcrLine("₱77.42", OcrBoundingBox(450, 1280, 610, 1320)),
        OcrLine("Dec 07, 2026", OcrBoundingBox(95, 1435, 360, 1475)),
        OcrLine("₱77.42", OcrBoundingBox(450, 1435, 610, 1475)),
        OcrLine("Dec 14, 2026", OcrBoundingBox(95, 1590, 360, 1630)),
        OcrLine("₱77.42", OcrBoundingBox(450, 1590, 610, 1630)),
        OcrLine("Dec 21, 2026", OcrBoundingBox(95, 1745, 360, 1785)),
        OcrLine("₱77.42", OcrBoundingBox(450, 1745, 610, 1785)),
        OcrLine("Dec 28, 2026", OcrBoundingBox(95, 1900, 360, 1940)),
        OcrLine("₱77.42", OcrBoundingBox(450, 1900, 610, 1940))
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("BillEase", draft.lender.value)
    assertEquals(ExtractionConfidence.HIGH, draft.lender.confidence)
    assertEquals("46976976", draft.loanReferenceId.value)
    assertEquals("Maya Cash Loan #46976976", draft.loanName.value)
    assertEquals(150_000L, draft.originalPrincipalCents.value)
    assertEquals(185_808L, draft.outstandingBalanceCents.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(7_742L, draft.installmentAmountCents.value)
    assertEquals(0, draft.paidInstallmentsCount.value)
    assertEquals(24, draft.remainingInstallmentsCount.value)
    assertEquals(RepaymentFrequency.WEEKLY, draft.frequency.value)
    assertEquals("2026-11-23", draft.nextDueDateIso.value)
    assertEquals(ExtractionConfidence.HIGH, draft.nextDueDateIso.confidence)
    assertEquals(DueDateStatus.CONFIRMED, draft.dueDateStatus)
    assertEquals("2027-05-03", draft.payoffTimeline.projectedPayoffDateIso)
    assertTrue(draft.validationIssues.none { it.severity == ImportSeverity.WARNING })
  }

  @Test
  fun reportedScanFailure_partialHeaderNoiseNeverOverridesRemainingBalanceWith3VisibleRowSum() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      width = 1220,
      height = 2712,
      lines = listOf(
        OcrLine("Schedule | Transactions | Documents"),
        OcrLine("G)) P1,000.00 GCash Cash Loan #46008372"),
        OcrLine("Installment ₱51.78 x 24"),
        OcrLine("Remaining ₱1,087.38"),
        OcrLine("Paid: 3/24"),
        OcrLine("Nov 02, 2026 | ₱51.78 | paid"),
        OcrLine("Nov 09, 2026 | ₱51.78 | paid"),
        OcrLine("Nov 16, 2026 | ₱51.78 | paid"),
        OcrLine("Nov 23, 2026 | ₱51.78"),
        OcrLine("Dec 01, 2026 | ₱51.78"),
        OcrLine("Dec 07, 2026 | ₱51.78")
      )
    )
    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(page, todayNov10)),
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals("BillEase", draft.lender.value)
    assertEquals(108_738L, draft.outstandingBalanceCents.value)
    assertTrue(draft.outstandingBalanceCents.value != 15_534L)
    assertEquals(21, draft.remainingInstallmentsCount.value)
    assertEquals(ExtractionConfidence.HIGH, draft.outstandingBalanceCents.confidence)
    assertEquals(ExtractionConfidence.HIGH, draft.installmentAmountCents.confidence)
    assertEquals(ExtractionConfidence.HIGH, draft.remainingInstallmentsCount.confidence)
  }
}
