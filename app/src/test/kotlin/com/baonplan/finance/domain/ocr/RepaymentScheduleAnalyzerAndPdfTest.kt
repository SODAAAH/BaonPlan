package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.InterestCalculationMethod
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.PrepaymentRuleType
import com.baonplan.finance.domain.model.ProjectionReliability
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ScheduleCompletenessStatus
import com.baonplan.finance.domain.model.SourceDocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.Deflater

class RepaymentScheduleAnalyzerAndPdfTest {

  private val refDate = LocalDate.of(2026, 11, 18)

  @Test
  fun pdfEmbeddedTextExtractor_extractsMultiPageDisclosureStatementAndScheduleWithFlateDecode() {
    val page1Stream = """
      BT
      /F1 12 Tf
      1 0 0 1 72 740 Tm
      (DISCLOSURE STATEMENT ON LOAN/CREDIT TRANSACTION) Tj
      1 0 0 1 72 715 Tm
      (Lending Company: Fuse Lending Inc.) Tj
      1 0 0 1 72 690 Tm
      (Product: GLoan Cash Loan) Tj
      1 0 0 1 72 665 Tm
      (Loan Reference No.: #GL-2026-9910) Tj
      1 0 0 1 72 640 Tm
      (Principal Amount: PHP 6,000.00) Tj
      1 0 0 1 72 615 Tm
      (Documentary Stamp Tax: PHP 45.00) Tj
      1 0 0 1 72 590 Tm
      (Processing Fee: PHP 180.00) Tj
      1 0 0 1 72 565 Tm
      (Net Proceeds Received: PHP 5,775.00) Tj
      1 0 0 1 72 540 Tm
      (Contractual Interest Rate: 2.99% per month flat add-on) Tj
      1 0 0 1 72 515 Tm
      (Effective Interest Rate: 35.88% p.a.) Tj
      1 0 0 1 72 490 Tm
      (Term: 6 monthly installments) Tj
      1 0 0 1 72 465 Tm
      (Monthly Installment: PHP 1,179.40) Tj
      1 0 0 1 72 440 Tm
      (Total Repayment: PHP 7,076.40) Tj
      1 0 0 1 72 415 Tm
      (Early Settlement: Rebate on unearned interest applies) Tj
      ET
    """.trimIndent()

    val page2Stream = """
      BT
      /F1 10 Tf
      1 0 0 1 50 750 Tm
      (AMORTIZATION SCHEDULE) Tj
      1 0 0 1 50 725 Tm
      (No. | Due Date | Principal | Interest | Installment | Balance) Tj
      1 0 0 1 50 700 Tm
      (1 | Nov 15, 2026 | 1,000.00 | 179.40 | 1,179.40 | 5,000.00) Tj
      1 0 0 1 50 675 Tm
      (2 | Dec 15, 2026 | 1,000.00 | 179.40 | 1,179.40 | 4,000.00) Tj
      1 0 0 1 50 650 Tm
      (3 | Jan 15, 2027 | 1,000.00 | 179.40 | 1,179.40 | 3,000.00) Tj
      1 0 0 1 50 625 Tm
      (4 | Feb 15, 2027 | 1,000.00 | 179.40 | 1,179.40 | 2,000.00) Tj
      1 0 0 1 50 600 Tm
      (5 | Mar 15, 2027 | 1,000.00 | 179.40 | 1,179.40 | 1,000.00) Tj
      1 0 0 1 50 575 Tm
      (6 | Apr 15, 2027 | 1,000.00 | 179.40 | 1,179.40 | 0.00) Tj
      ET
    """.trimIndent()

    val pdfBytes = buildMinimalTwoPagePdf(page1Stream, page2Stream, compressStreams = true)
    val outcome = PdfEmbeddedTextExtractor.extractFromBytes(pdfBytes)
    assertTrue("Expected PdfTextExtractionOutcome.Success, got $outcome", outcome is PdfTextExtractionOutcome.Success)
    val success = outcome as PdfTextExtractionOutcome.Success
    assertEquals(2, success.totalPages)
    assertEquals(2, success.pages.size)
    assertTrue(success.pages[0].hasSelectableText)
    assertTrue(success.pages[1].hasSelectableText)

    val docPages = success.pages.mapIndexed { idx, p ->
      OcrDocumentPage(
        screenshotIndex = idx,
        lines = p.lines,
        rawFullText = p.rawText,
        sourceDocumentId = "pdf_disclosure",
        documentTitle = "GLoan Disclosure.pdf",
        pageNumber = p.pageNumber,
        totalPagesInDocument = 2,
        isPdfPage = true
      )
    }

    // Also add a mobile app screenshot showing installment #1 is already paid!
    val appScreenshot = OcrDocumentPage(
      screenshotIndex = 2,
      lines = listOf(
        OcrLine("GCash GLoan #GL-2026-9910"),
        OcrLine("Remaining Balance: ₱5,897.00"),
        OcrLine("Paid: 1/6"),
        OcrLine("Nov 15, 2026 | ₱1,179.40 | Paid"),
        OcrLine("Dec 15, 2026 | ₱1,179.40 | Upcoming")
      ),
      documentTitle = "GCash App Screenshot"
    )

    val extractions = (docPages + appScreenshot).map { DebtOcrExtractor.extract(it, refDate) }
    val mergeOutcome = MultiScreenshotDebtMerger.merge(
      pages = extractions,
      existingValidator = { DebtImportValidator.validateDraft(it) }
    )

    assertTrue("Expected SingleLoan, got $mergeOutcome", mergeOutcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (mergeOutcome as ScanAnalysisOutcome.SingleLoan).draft

    // Contractual authority from Disclosure Statement + payment state from App Screenshot
    assertEquals("Fuse Lending Inc.", draft.lendingCompany.value)
    assertEquals(600_000L, draft.originalPrincipalCents.value)
    assertEquals(4_500L, draft.documentaryStampTaxCents.value)
    assertEquals(18_000L, draft.processingFeeCents.value)
    assertEquals(577_500L, draft.amountReceivedCents.value)
    assertEquals(6, draft.originalInstallmentCount.value)
    assertEquals(1, draft.paidInstallmentsCount.value)
    assertEquals(5, draft.remainingInstallmentsCount.value)
    assertEquals(589_700L, draft.outstandingBalanceCents.value)

    // Contractual terms parsed accurately
    assertEquals(2.99, draft.contractualTerms.monthlyInterestRatePercent!!, 0.001)
    assertEquals(35.88, draft.contractualTerms.effectiveInterestRatePercent!!, 0.001)
    assertEquals(InterestCalculationMethod.FLAT_ADD_ON, draft.contractualTerms.interestCalculationMethod)
    assertEquals(PrepaymentRuleType.REBATE_UNEARNED_INTEREST, draft.contractualTerms.prepaymentRule)

    // Complete 6-row schedule reconstructed with component breakdown and payoff timeline
    assertFalse(draft.isSchedulePartial)
    assertEquals(6, draft.installmentRows.size)
    assertEquals(100_000L, draft.installmentRows[0].principalComponentCents)
    assertEquals(17_940L, draft.installmentRows[0].interestComponentCents)
    assertEquals(ExtractedInstallmentStatus.PAID, draft.installmentRows[0].status)
    assertEquals(ScheduleCompletenessStatus.COMPLETE_SCHEDULE, draft.payoffTimeline.completenessStatus)
    assertEquals(ProjectionReliability.CONFIRMED_BY_SCHEDULE, draft.payoffTimeline.reliability)
    assertEquals("2027-04-15", draft.payoffTimeline.contractualMaturityDateIso)
    assertEquals("2027-04-15", draft.payoffTimeline.projectedPayoffDateIso)
    assertEquals("2026-12-15", draft.nextDueDateIso.value)
  }

  @Test
  fun pdfEmbeddedTextExtractor_handlesPasswordProtectedAndCorruptedPdfsSafely() {
    val encryptedPdf = "%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Count 1 >> endobj\ntrailer << /Root 1 0 R /Encrypt 3 0 R >>\n%%EOF".toByteArray()
    val encOutcome = PdfEmbeddedTextExtractor.extractFromBytes(encryptedPdf)
    assertTrue(encOutcome is PdfTextExtractionOutcome.PasswordProtected)

    val garbageOutcome = PdfEmbeddedTextExtractor.extractFromBytes("Not a PDF file".toByteArray())
    assertTrue(garbageOutcome is PdfTextExtractionOutcome.CorruptedOrUnsupported)

    // Image-only PDF (valid PDF structure with no BT..ET text) flags hasSelectableText = false for OCR fallback
    val imageOnlyPdf = buildMinimalTwoPagePdf("q 100 0 0 100 0 0 cm /Im1 Do Q", "q 100 0 0 100 0 0 cm /Im2 Do Q", compressStreams = false)
    val imageOutcome = PdfEmbeddedTextExtractor.extractFromBytes(imageOnlyPdf)
    assertTrue(imageOutcome is PdfTextExtractionOutcome.Success)
    val imgSuccess = imageOutcome as PdfTextExtractionOutcome.Success
    assertEquals(2, imgSuccess.totalPages)
    assertFalse(imgSuccess.pages[0].hasSelectableText)
    assertFalse(imgSuccess.pages[1].hasSelectableText)
  }

  @Test
  fun overlappingMultiScreenshotSchedules_deduplicateAndReconstructCompleteTimeline() {
    val page1 = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase Installment Loan #BE-8821"),
        OcrLine("Loan Amount: ₱6,000.00"),
        OcrLine("Installment: ₱1,100.00 x 6"),
        OcrLine("1 | Oct 15, 2026 | ₱1,100.00 | Paid"),
        OcrLine("2 | Nov 15, 2026 | ₱1,100.00 | Paid"),
        OcrLine("3 | Dec 15, 2026 | ₱1,100.00 | Upcoming")
      )
    )
    val page2 = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("BillEase Installment Loan #BE-8821"),
        OcrLine("3 | Dec 15, 2026 | ₱1,100.00 | Upcoming"),
        OcrLine("4 | Jan 15, 2027 | ₱1,100.00 | Upcoming"),
        OcrLine("5 | Feb 15, 2027 | ₱1,100.00 | Upcoming")
      )
    )
    val page3 = OcrDocumentPage(
      screenshotIndex = 2,
      lines = listOf(
        OcrLine("BillEase Installment Loan #BE-8821"),
        OcrLine("5 | Feb 15, 2027 | ₱1,100.00 | Upcoming"),
        OcrLine("6 | Mar 15, 2027 | ₱1,100.00 | Upcoming")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(page1, page2, page3).map { DebtOcrExtractor.extract(it, refDate) }
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals(6, draft.installmentRows.size)
    assertFalse(draft.isSchedulePartial)
    assertEquals(listOf("2026-10-15", "2026-11-15", "2026-12-15", "2027-01-15", "2027-02-15", "2027-03-15"), draft.installmentRows.map { it.dueDateIso })
    assertEquals(2, draft.paidInstallmentsCount.value)
    assertEquals(4, draft.remainingInstallmentsCount.value)
    assertEquals(440_000L, draft.remainingInstallmentTotalCents.value)
    assertEquals("2027-03-15", draft.payoffTimeline.projectedPayoffDateIso)
    assertEquals(ScheduleCompletenessStatus.COMPLETE_SCHEDULE, draft.payoffTimeline.completenessStatus)
  }

  @Test
  fun partialSchedule_neverFabricatesUnseenRowsAndProjectsFromRemainingCountAndFrequency() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("P1,000.00 GCash Cash Loan #46008372"),
        OcrLine("Oct 23, 2026"),
        OcrLine("Installment | ₱51.78 x 24"),
        OcrLine("Remaining | ₱1,087.38"),
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
      pages = listOf(DebtOcrExtractor.extract(page, refDate))
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    // Only 6 visible rows stored — never fabricating 18 unseen schedule rows
    assertTrue(draft.isSchedulePartial)
    assertEquals(6, draft.installmentRows.size)
    assertEquals(108_738L, draft.outstandingBalanceCents.value)
    assertEquals(21, draft.remainingInstallmentsCount.value)
    assertEquals(ScheduleCompletenessStatus.PARTIAL_SCHEDULE, draft.payoffTimeline.completenessStatus)
    assertEquals(ProjectionReliability.ESTIMATED_FROM_PARTIAL, draft.payoffTimeline.reliability)
    assertNotNull(draft.payoffTimeline.projectedPayoffDateIso)
    assertNotNull(draft.payoffTimeline.missingInformationExplanation)
  }

  @Test
  fun restructuredScheduleVersion2_retainsV1SnapshotAndUpdatesCurrentScheduledMaturity() {
    val originalAgreement = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("LOAN AGREEMENT"),
        OcrLine("Tala Financing Philippines Inc."),
        OcrLine("Loan Reference: #TL-7741"),
        OcrLine("Principal: ₱10,000.00"),
        OcrLine("Installment: ₱1,000.00 x 12"),
        OcrLine("Maturity Date: Jun 15, 2027")
      )
    )
    val restructuringNotice = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("RESTRUCTURED REPAYMENT SCHEDULE"),
        OcrLine("Effective Date: Nov 20, 2026"),
        OcrLine("Tala Financing Philippines Inc."),
        OcrLine("Loan Reference: #TL-7741"),
        OcrLine("Principal: ₱10,000.00"),
        OcrLine("Revised Installment: ₱750.00 x 18"),
        OcrLine("Final Due Date: Dec 15, 2027")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(
        DebtOcrExtractor.extract(originalAgreement, refDate),
        DebtOcrExtractor.extract(restructuringNotice, refDate)
      )
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals(2, draft.scheduleVersion)
    assertEquals(2, draft.scheduleVersions.size)
    assertEquals("2027-06-15", draft.payoffTimeline.contractualMaturityDateIso)
    assertEquals("2027-12-15", draft.payoffTimeline.currentScheduledPayoffDateIso)
    assertEquals(75_000L, draft.installmentAmountCents.value)
    assertEquals(18, draft.originalInstallmentCount.value)
  }

  @Test
  fun revolvingCreditLine_neverInventsFixedPayoffDate() {
    val gcreditPage = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("GCash GCredit Statement"),
        OcrLine("Credit Limit: ₱15,000.00"),
        OcrLine("Available Credit: ₱11,500.00"),
        OcrLine("Statement Balance: ₱3,500.00"),
        OcrLine("Minimum Amount Due: ₱350.00"),
        OcrLine("Due Date: Dec 05, 2026")
      )
    )

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = listOf(DebtOcrExtractor.extract(gcreditPage, refDate))
    )
    assertTrue(outcome is ScanAnalysisOutcome.SingleLoan)
    val draft = (outcome as ScanAnalysisOutcome.SingleLoan).draft

    assertEquals(LoanProductClassification.REVOLVING_CREDIT, draft.productClassification.value)
    assertEquals(ScheduleCompletenessStatus.REVOLVING_NO_FIXED_PAYOFF, draft.payoffTimeline.completenessStatus)
    assertEquals(ProjectionReliability.NOT_APPLICABLE_REVOLVING, draft.payoffTimeline.reliability)
    assertNull(draft.payoffTimeline.projectedPayoffDateIso)
    assertNull(draft.payoffTimeline.contractualMaturityDateIso)
    assertTrue(draft.payoffTimeline.missingInformationExplanation!!.contains("Revolving credit", ignoreCase = true))
  }

  @Test
  fun deterministicAmortizationMethods_andHypotheticalScenarios_produceExactCentavos() {
    // 1. Flat add-on: ₱10,000 principal, 2% monthly for 6 months -> ₱1,200 interest + ₱300 fee = ₱11,500 total
    val flatTable = RepaymentScheduleAnalyzer.generateContractualAmortizationTable(
      principalCents = 1_000_000L,
      installmentCount = 6,
      startDate = LocalDate.of(2026, 12, 1),
      frequency = RepaymentFrequency.MONTHLY,
      method = InterestCalculationMethod.FLAT_ADD_ON,
      monthlyRatePercent = 2.0,
      totalServiceFeeCents = 30_000L
    )
    assertEquals(6, flatTable.size)
    assertEquals(1_000_000L, flatTable.sumOf { it.principalComponentCents ?: 0L })
    assertEquals(120_000L, flatTable.sumOf { it.interestComponentCents ?: 0L })
    assertEquals(30_000L, flatTable.sumOf { it.serviceFeeComponentCents ?: 0L })
    assertEquals(1_150_000L, flatTable.sumOf { it.totalInstallmentCents ?: 0L })
    assertEquals(0L, flatTable.last().remainingPrincipalAfterCents)

    // 2. Reducing balance: ₱12,000 principal, 24% p.a. (2% monthly) for 12 months
    val reducingTable = RepaymentScheduleAnalyzer.generateContractualAmortizationTable(
      principalCents = 1_200_000L,
      installmentCount = 12,
      startDate = LocalDate.of(2026, 12, 1),
      frequency = RepaymentFrequency.MONTHLY,
      method = InterestCalculationMethod.REDUCING_BALANCE,
      annualRatePercent = 24.0
    )
    assertEquals(12, reducingTable.size)
    assertEquals(1_200_000L, reducingTable.sumOf { it.principalComponentCents ?: 0L })
    assertEquals(0L, reducingTable.last().remainingPrincipalAfterCents)
    assertTrue(
      "Interest component must decline under reducing balance",
      reducingTable.first().interestComponentCents!! > reducingTable.last().interestComponentCents!!
    )

    // 3. Equal principal: ₱12,000 principal, 1% monthly for 4 months
    val equalPrincipalTable = RepaymentScheduleAnalyzer.generateContractualAmortizationTable(
      principalCents = 1_200_000L,
      installmentCount = 4,
      startDate = LocalDate.of(2026, 12, 1),
      frequency = RepaymentFrequency.MONTHLY,
      method = InterestCalculationMethod.EQUAL_PRINCIPAL,
      monthlyRatePercent = 1.0
    )
    assertEquals(4, equalPrincipalTable.size)
    assertEquals(listOf(300_000L, 300_000L, 300_000L, 300_000L), equalPrincipalTable.map { it.principalComponentCents })
    assertEquals(listOf(12_000L, 9_000L, 6_000L, 3_000L), equalPrincipalTable.map { it.interestComponentCents })
    assertEquals(0L, equalPrincipalTable.last().remainingPrincipalAfterCents)

    // 4. Hypothetical scenarios are always flagged isHypothetical = true
    val scenarios = RepaymentScheduleAnalyzer.computeHypotheticalScenarios(
      remainingBalanceCents = 500_000L,
      installmentAmountCents = 100_000L,
      remainingInstallmentsCount = 5,
      nextDueDateIso = "2026-12-01",
      frequency = RepaymentFrequency.MONTHLY,
      contractualTerms = ContractualTermsSummary(
        prepaymentRule = PrepaymentRuleType.REBATE_UNEARNED_INTEREST,
        latePaymentFeeRule = "5% late fee",
        gracePeriodDays = 3
      )
    )
    assertTrue(scenarios.size >= 2)
    assertTrue(scenarios.all { it.isHypothetical })
  }

  private fun buildMinimalTwoPagePdf(
    page1Content: String,
    page2Content: String,
    compressStreams: Boolean
  ): ByteArray {
    fun encodeStream(raw: String): Pair<String, ByteArray> {
      val bytes = raw.toByteArray(Charsets.ISO_8859_1)
      return if (compressStreams) {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
          val n = deflater.deflate(buf)
          out.write(buf, 0, n)
        }
        deflater.end()
        val compressed = out.toByteArray()
        "/Filter /FlateDecode /Length ${compressed.size}" to compressed
      } else {
        "/Length ${bytes.size}" to bytes
      }
    }

    val (dict1, data1) = encodeStream(page1Content)
    val (dict2, data2) = encodeStream(page2Content)

    val out = ByteArrayOutputStream()
    val offsets = mutableListOf<Int>()
    fun writeStr(s: String) {
      out.write(s.toByteArray(Charsets.ISO_8859_1))
    }

    writeStr("%PDF-1.4\n")

    offsets.add(out.size())
    writeStr("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")

    offsets.add(out.size())
    writeStr("2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >> endobj\n")

    offsets.add(out.size())
    writeStr("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R >> endobj\n")

    offsets.add(out.size())
    writeStr("4 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R >> endobj\n")

    offsets.add(out.size())
    writeStr("5 0 obj << $dict1 >> stream\n")
    out.write(data1)
    writeStr("\nendstream endobj\n")

    offsets.add(out.size())
    writeStr("6 0 obj << $dict2 >> stream\n")
    out.write(data2)
    writeStr("\nendstream endobj\n")

    val xrefOffset = out.size()
    writeStr("xref\n0 7\n0000000000 65535 f \n")
    for (off in offsets) {
      writeStr(String.format("%010d 00000 n \n", off))
    }
    writeStr("trailer << /Size 7 /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF")
    return out.toByteArray()
  }
}
