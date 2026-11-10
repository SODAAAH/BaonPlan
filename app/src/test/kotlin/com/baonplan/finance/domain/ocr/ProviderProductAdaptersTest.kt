package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProviderProductAdaptersTest {

  private val refDate = LocalDate.of(2026, 11, 10)

  @Test
  fun gcashGloanAndGgives_classifyDistinctlyAndHonorDisclosureTerms() {
    val gloanPage = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("GCash GLoan Disclosure Statement"),
        OcrLine("Loan Account Number: GL-90112"),
        OcrLine("Loan Principal: ₱5,000.00"),
        OcrLine("Processing Fee: ₱150.00"),
        OcrLine("Interest: ₱600.00"),
        OcrLine("Total Amount Payable: ₱5,750.00"),
        OcrLine("Outstanding Balance: ₱4,600.00"),
        OcrLine("Monthly Installment: ₱1,150.00"),
        OcrLine("Due Date: Dec 15, 2026")
      )
    )
    val gloan = DebtOcrExtractor.extract(gloanPage, refDate)
    assertEquals("GCash (GLoan)", gloan.lender.value)
    assertEquals(LoanProductClassification.FIXED_INSTALLMENT_LOAN, gloan.productClassification.value)
    assertEquals(500_000L, gloan.originalPrincipalCents.value)
    assertEquals(575_000L, gloan.totalRepaymentCents.value)
    assertEquals(460_000L, gloan.outstandingBalanceCents.value)

    val ggivesPage = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("GCash GGives"),
        OcrLine("Merchant: Abenson Online"),
        OcrLine("Loan Reference: GG-7741"),
        OcrLine("Loan Amount: ₱12,000.00"),
        OcrLine("Remaining Balance: ₱9,000.00"),
        OcrLine("Installment Amount: ₱1,500.00"),
        OcrLine("Paid: 2/8")
      )
    )
    val ggives = DebtOcrExtractor.extract(ggivesPage, refDate)
    assertEquals("GCash (GGives)", ggives.lender.value)
    assertEquals(LoanProductClassification.BNPL_INSTALLMENT, ggives.productClassification.value)
    assertEquals(6, ggives.remainingInstallmentsCount.value)
  }

  @Test
  fun billeaseProducts_separateCashInstallmentAndRevolvingCreditLine() {
    val creditLinePage = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase Credit Line"),
        OcrLine("Credit Limit: ₱20,000.00"),
        OcrLine("Available Credit: ₱13,500.00"),
        OcrLine("Statement Balance: ₱6,500.00"),
        OcrLine("Minimum Amount Due: ₱500.00"),
        OcrLine("Statement Date: Nov 01, 2026"),
        OcrLine("Due Date: Nov 16, 2026")
      )
    )
    val creditLine = DebtOcrExtractor.extract(creditLinePage, refDate)
    assertEquals(LoanProductClassification.REVOLVING_CREDIT, creditLine.productClassification.value)
    assertEquals(2_000_000L, creditLine.creditLimitCents.value)
    assertEquals(1_350_000L, creditLine.availableCreditCents.value)
    assertEquals(650_000L, creditLine.statementBalanceCents.value)
    assertEquals(50_000L, creditLine.minimumDueCents.value)
    // Must NOT treat Available Credit (₱13,500) or Minimum Due (₱500) as debt balance
    assertNull(creditLine.originalPrincipalCents.value)

    val cashLoanPage = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("BillEase Cash Loan"),
        OcrLine("Loan ID: BE-CASH-441"),
        OcrLine("Loan Amount: ₱8,000.00"),
        OcrLine("Remaining Balance: ₱6,000.00"),
        OcrLine("Monthly Installment: ₱2,000.00")
      )
    )
    val cashLoan = DebtOcrExtractor.extract(cashLoanPage, refDate)
    assertEquals(LoanProductClassification.FIXED_INSTALLMENT_LOAN, cashLoan.productClassification.value)

    // Merger MUST keep BillEase Credit Line and BillEase Cash Loan strictly separate
    val outcome = MultiScreenshotDebtMerger.merge(listOf(creditLine, cashLoan))
    assertTrue(outcome is ScanAnalysisOutcome.MultipleLoans)
    assertEquals(2, (outcome as ScanAnalysisOutcome.MultipleLoans).drafts.size)
  }

  @Test
  fun mayaPersonalLoanAndEasyCredit_distinguishInstallmentVsRevolvingCredit() {
    val easyCredit = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Maya Easy Credit"),
          OcrLine("Credit Limit: ₱15,000.00"),
          OcrLine("Available Credit: ₱11,200.00"),
          OcrLine("Statement Balance: ₱3,800.00"),
          OcrLine("Minimum Amount Due: ₱380.00"),
          OcrLine("Billing Period: Oct 15 - Nov 14, 2026"),
          OcrLine("Due Date: Nov 29, 2026")
        )
      ),
      refDate
    )
    assertEquals("Maya (Easy Credit)", easyCredit.lender.value)
    assertEquals(LoanProductClassification.REVOLVING_CREDIT, easyCredit.productClassification.value)
    assertEquals(1_500_000L, easyCredit.creditLimitCents.value)
    assertEquals(1_120_000L, easyCredit.availableCreditCents.value)
    assertEquals(380_000L, easyCredit.statementBalanceCents.value)
    assertEquals(38_000L, easyCredit.minimumDueCents.value)

    val personalLoan = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 1,
        lines = listOf(
          OcrLine("Maya Personal Loan"),
          OcrLine("Loan Account No: MPL-9921"),
          OcrLine("Loan Amount: ₱25,000.00"),
          OcrLine("Remaining Balance: ₱18,750.00"),
          OcrLine("Monthly Amortization: ₱2,500.00"),
          OcrLine("Paid: 3/12")
        )
      ),
      refDate
    )
    assertEquals("Maya (Personal Loan)", personalLoan.lender.value)
    assertEquals(LoanProductClassification.FIXED_INSTALLMENT_LOAN, personalLoan.productClassification.value)
    assertEquals(9, personalLoan.remainingInstallmentsCount.value)
  }

  @Test
  fun shopeeSpayLaterAndSloan_preserveRefundedRowsAndSeparateProducts() {
    val spayPage = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Shopee SPayLater"),
        OcrLine("Order #SP-77210"),
        OcrLine("Remaining Balance: ₱1,000.00"),
        OcrLine("#1 | Oct 15, 2026 | ₱500.00 | Paid"),
        OcrLine("#2 | Nov 15, 2026 | ₱500.00 | Refunded"),
        OcrLine("#3 | Dec 15, 2026 | ₱500.00 | Upcoming"),
        OcrLine("#4 | Jan 15, 2027 | ₱500.00 | Upcoming")
      )
    )
    val spay = DebtOcrExtractor.extract(spayPage, refDate)
    assertEquals(LoanProductClassification.BNPL_INSTALLMENT, spay.productClassification.value)
    assertEquals(ExtractedInstallmentStatus.REFUNDED, spay.installmentRows[1].status)
    // Refunded row MUST NOT be counted as unpaid obligation!
    assertEquals(2, spay.remainingInstallmentsCount.value)
    assertEquals(100_000L, spay.remainingInstallmentTotalCents.value)

    val sloanPage = OcrDocumentPage(
      screenshotIndex = 1,
      lines = listOf(
        OcrLine("Shopee SLoan"),
        OcrLine("Loan ID: SL-3310"),
        OcrLine("Loan Amount: ₱10,000.00"),
        OcrLine("Remaining Balance: ₱7,500.00"),
        OcrLine("Monthly Installment: ₱2,500.00")
      )
    )
    val sloan = DebtOcrExtractor.extract(sloanPage, refDate)
    assertEquals(LoanProductClassification.FIXED_INSTALLMENT_LOAN, sloan.productClassification.value)

    val outcome = MultiScreenshotDebtMerger.merge(listOf(spay, sloan))
    assertTrue(outcome is ScanAnalysisOutcome.MultipleLoans)
  }

  @Test
  fun atomeAndCimb_distinguishRevolvingCreditFromConvertedInstallments() {
    val atomeCard = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Atome Card"),
          OcrLine("Credit Limit: ₱30,000.00"),
          OcrLine("Available Limit: ₱22,500.00"),
          OcrLine("Statement Balance: ₱7,500.00"),
          OcrLine("Minimum Payment Due: ₱750.00"),
          OcrLine("Payment Due Date: Dec 05, 2026")
        )
      ),
      refDate
    )
    assertEquals("Atome", atomeCard.lender.value)
    assertEquals(LoanProductClassification.REVOLVING_CREDIT, atomeCard.productClassification.value)
    assertEquals(750_000L, atomeCard.statementBalanceCents.value)

    val cimbConverted = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 1,
        lines = listOf(
          OcrLine("CIMB REVI Credit Installment Conversion"),
          OcrLine("Converted to Installment"),
          OcrLine("Plan Reference: REVI-CONV-88"),
          OcrLine("Principal Amount: ₱6,000.00"),
          OcrLine("Remaining Balance: ₱4,000.00"),
          OcrLine("Monthly Installment: ₱1,000.00"),
          OcrLine("Paid: 2/6")
        )
      ),
      refDate
    )
    assertEquals(LoanProductClassification.CREDIT_BILL_INSTALLMENT, cimbConverted.productClassification.value)
    assertEquals(4, cimbConverted.remainingInstallmentsCount.value)
  }

  @Test
  fun homeCreditSkyroFlexiVersionedTalaAndGeneric_extractAccurately() {
    // 7. Home Credit
    val homeCredit = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Home Credit Product Loan"),
          OcrLine("Contract Number: 4120987654"),
          OcrLine("Financed Amount: ₱18,000.00"),
          OcrLine("Remaining Balance: ₱12,000.00"),
          OcrLine("Monthly Installment: ₱2,000.00"),
          OcrLine("Due Date: Dec 10, 2026")
        )
      ),
      refDate
    )
    assertEquals("Home Credit", homeCredit.lender.value)
    assertEquals("4120987654", homeCredit.loanReferenceId.value)
    assertEquals(LoanProductClassification.BNPL_INSTALLMENT, homeCredit.productClassification.value)

    // 8. Skyro Flexi restructured schedule v2 supersedes v1 on merge
    val skyroV1 = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Skyro Cash Loan"),
          OcrLine("Contract No: SKY-7788"),
          OcrLine("Original Schedule"),
          OcrLine("Loan Amount: ₱10,000.00"),
          OcrLine("Monthly Installment: ₱2,200.00"),
          OcrLine("Total Installments: 6"),
          OcrLine("#1 | Nov 15, 2026 | ₱2,200.00 | Upcoming")
        )
      ),
      refDate
    )
    val skyroV2 = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 1,
        lines = listOf(
          OcrLine("Skyro Flexi Restructured Schedule"),
          OcrLine("Contract No: SKY-7788"),
          OcrLine("Effective Date: Nov 01, 2026"),
          OcrLine("Loan Amount: ₱10,000.00"),
          OcrLine("Remaining Balance: ₱10,800.00"),
          OcrLine("Monthly Installment: ₱1,350.00"),
          OcrLine("Total Installments: 8"),
          OcrLine("#1 | Dec 15, 2026 | ₱1,350.00 | Upcoming"),
          OcrLine("#2 | Jan 15, 2027 | ₱1,350.00 | Upcoming")
        )
      ),
      refDate
    )
    assertEquals(2, skyroV2.scheduleVersion)
    assertEquals("2026-11-01", skyroV2.scheduleEffectiveDateIso)
    val skyroMerged = (MultiScreenshotDebtMerger.merge(listOf(skyroV1, skyroV2)) as ScanAnalysisOutcome.SingleLoan).draft
    assertEquals(2, skyroMerged.scheduleVersion)
    assertEquals(135_000L, skyroMerged.installmentAmountCents.value)
    assertEquals(8, skyroMerged.originalInstallmentCount.value)
    assertTrue(skyroMerged.mergeNotes.any { it.contains("restructured schedule v2", ignoreCase = true) })

    // 9. Tala lump sum
    val tala = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Tala Cash Loan"),
          OcrLine("Amount Disbursed: ₱3,000.00"),
          OcrLine("Service Fee: ₱450.00"),
          OcrLine("Total Repayment: ₱3,450.00"),
          OcrLine("Single Payment Due Date: Dec 01, 2026")
        )
      ),
      refDate
    )
    assertEquals("Tala", tala.lender.value)
    assertEquals(LoanProductClassification.SHORT_TERM_LUMP_SUM_LOAN, tala.productClassification.value)
    assertEquals(RepaymentFrequency.ONE_TIME, tala.frequency.value)
    assertEquals(300_000L, tala.disbursedAmountCents.value)

    // 10. Generic unknown lender never fabricates provider name
    val generic = DebtOcrExtractor.extract(
      OcrDocumentPage(
        screenshotIndex = 0,
        lines = listOf(
          OcrLine("Personal Loan Summary"),
          OcrLine("Remaining Balance: ₱4,500.00"),
          OcrLine("Weekly Payment: ₱500.00"),
          OcrLine("Next Due Date: Nov 21, 2026")
        )
      ),
      refDate
    )
    assertNull(generic.lender.value)
    assertEquals(FieldOrigin.NOT_DETECTED, generic.productClassification.origin)
    assertNull(generic.productClassification.value)
    assertEquals(450_000L, generic.outstandingBalanceCents.value)
  }
}
