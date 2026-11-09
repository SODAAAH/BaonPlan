package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DebtOcrExtractorTest {

  private val refDate = LocalDate.of(2026, 11, 10)

  private fun twoColumnPage(
    index: Int = 0,
    rows: List<Pair<String, String>>,
    singleHeaders: List<String> = emptyList()
  ): OcrDocumentPage {
    val lines = mutableListOf<OcrLine>()
    var y = 40
    for (header in singleHeaders) {
      lines.add(OcrLine(header, OcrBoundingBox(40, y, 500, y + 28)))
      y += 44
    }
    for ((left, right) in rows) {
      lines.add(OcrLine(left, OcrBoundingBox(40, y, 320, y + 28)))
      if (right.isNotBlank()) {
        lines.add(OcrLine(right, OcrBoundingBox(380, y, 700, y + 28)))
      }
      y += 44
    }
    return OcrDocumentPage(screenshotIndex = index, width = 760, height = y + 40, lines = lines)
  }

  @Test
  fun extractsCompleteLoanDetailsIncludingFeesAndCountsFromTwoColumnBoxes() {
    val page = twoColumnPage(
      singleHeaders = listOf("SPayLater Loan Details", "Order #SP-88412", "Active · Monthly"),
      rows = listOf(
        "Item Name" to "Campus Desk Lamp",
        "Loan Amount" to "₱3,000.00",
        "Amount Received" to "₱2,900.00",
        "Remaining Balance" to "₱1,800.00",
        "Interest" to "₱300.00",
        "Interest Rate" to "3.95%",
        "Service Fee" to "₱150.00",
        "Processing Fee" to "₱100.00",
        "Convenience Fee" to "₱50.00",
        "Total Repayment" to "₱3,600.00",
        "Monthly Installment" to "₱600.00",
        "Progress" to "3/6 paid",
        "Remaining Installments" to "3",
        "Next Payment Due" to "Nov 16, 2026",
        "Final Payment Date" to "Jan 16, 2027"
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals("Shopee (SPayLater)", ex.lender.value)
    assertEquals("SP-88412", ex.loanReferenceId.value)
    assertEquals("Campus Desk Lamp", ex.loanName.value)
    assertEquals(300_000L, ex.originalPrincipalCents.value)
    assertEquals(290_000L, ex.amountReceivedCents.value)
    assertEquals(180_000L, ex.outstandingBalanceCents.value)
    assertEquals(30_000L, ex.interestCents.value) // Ignored "3.95%"!
    assertEquals(15_000L, ex.serviceFeeCents.value)
    assertEquals(10_000L, ex.processingFeeCents.value)
    assertEquals(5_000L, ex.otherFeesCents.value)
    assertEquals(360_000L, ex.totalRepaymentCents.value)
    assertEquals(60_000L, ex.installmentAmountCents.value)
    assertEquals(6, ex.originalInstallmentCount.value)
    assertEquals(3, ex.paidInstallmentsCount.value)
    assertEquals(3, ex.remainingInstallmentsCount.value)
    assertEquals(RepaymentFrequency.MONTHLY, ex.frequency.value)
    assertEquals("2026-11-16", ex.nextDueDateIso.value)
    assertEquals("2027-01-16", ex.finalDueDateIso.value)
    assertEquals(DebtStatus.ACTIVE, ex.loanStatus.value)
    // Scalar summary dates must NOT create fake empty schedule rows!
    assertTrue(ex.installmentRows.isEmpty())
  }

  @Test
  fun keepsBalanceAndRemainingInstallmentTotalStrictlySeparate() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("BillEase Loan Summary"),
        OcrLine("Current Balance: ₱1,500.00"),
        OcrLine("Remaining Installment Total: ₱1,850.00"),
        OcrLine("Due Date: Nov 16, 2026")
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals("BillEase", ex.lender.value)
    assertEquals(150_000L, ex.outstandingBalanceCents.value)
    assertEquals(185_000L, ex.remainingInstallmentTotalCents.value)
    assertEquals("2026-11-16", ex.nextDueDateIso.value)
    assertTrue(ex.installmentRows.isEmpty())
    assertTrue(ex.issues.any { it.title.contains("Balance differs") })
  }

  @Test
  fun extractsScheduleOnlyAndPreservesPaidVsUpcomingWithoutFabricatingFees() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("GCash GLoan Repayment Schedule"),
        OcrLine("#1 | Oct 16, 2026 | ₱416.67 | Paid"),
        OcrLine("#2 | Nov 16, 2026 | ₱416.67 | Upcoming"),
        OcrLine("#3 | Dec 16, 2026 | ₱416.67 | Upcoming"),
        OcrLine("#4 | Jan 16, 2027 | ₱416.67 | Upcoming")
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals(ExtractedDocumentKind.SCHEDULE_ONLY, ex.documentKind)
    assertEquals("GCash (GLoan)", ex.lender.value)
    assertEquals(FieldOrigin.NOT_DETECTED, ex.originalPrincipalCents.origin)
    assertNull(ex.originalPrincipalCents.value)
    assertEquals(FieldOrigin.NOT_DETECTED, ex.interestCents.origin)
    assertEquals(FieldOrigin.NOT_DETECTED, ex.serviceFeeCents.origin)

    assertEquals(4, ex.installmentRows.size)
    assertEquals(ExtractedInstallmentStatus.PAID, ex.installmentRows[0].status)
    assertEquals("2026-10-16", ex.installmentRows[0].dueDateIso)
    assertEquals(41_667L, ex.installmentRows[0].amountCents)
    assertEquals(ExtractedInstallmentStatus.UPCOMING, ex.installmentRows[1].status)
    assertEquals("2026-11-16", ex.nextDueDateIso.value)
    assertEquals("2027-01-16", ex.finalDueDateIso.value)
    assertEquals("2026-10-16", ex.lastPaidDateIso.value)
    assertEquals(RepaymentFrequency.MONTHLY, ex.frequency.value)
  }

  @Test
  fun irregularSchedulePreservesExactDatesAndAssignsIrregularFrequency() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Atome Custom Schedule"),
        OcrLine("Jan 5, 2026 | ₱500.00 | Paid"),
        OcrLine("Jan 19, 2026 | ₱500.00 | Upcoming"),
        OcrLine("Feb 2, 2026 | ₱500.00 | Upcoming"),
        OcrLine("Feb 27, 2026 | ₱620.00 | Upcoming"),
        OcrLine("Mar 19, 2026 | ₱620.00 | Upcoming")
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals(RepaymentFrequency.IRREGULAR, ex.frequency.value)
    assertEquals(
      listOf("2026-01-05", "2026-01-19", "2026-02-02", "2026-02-27", "2026-03-19"),
      ex.installmentRows.map { it.dueDateIso }
    )
    assertEquals("2026-01-19", ex.nextDueDateIso.value)
    assertEquals("2026-03-19", ex.finalDueDateIso.value)
  }

  @Test
  fun fewerThanThreeDatesNeverGuessesWeeklyBiweeklyOrMonthly() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Maya Credit Payments"),
        OcrLine("Nov 16, 2026 | ₱750.00 | Upcoming"),
        OcrLine("Dec 16, 2026 | ₱750.00 | Upcoming")
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals(2, ex.installmentRows.size)
    assertNull(ex.frequency.value)
    assertEquals(FieldOrigin.NOT_DETECTED, ex.frequency.origin)
  }

  @Test
  fun missingAmountsAndPercentagesStayNotDetected() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Tala Cash Loan"),
        OcrLine("Interest Rate: 0.5% per day"),
        OcrLine("Next Due Date: Nov 20, 2026")
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals("Tala", ex.lender.value)
    assertNull(ex.interestCents.value)
    assertNull(ex.outstandingBalanceCents.value)
    assertEquals(FieldOrigin.NOT_DETECTED, ex.outstandingBalanceCents.origin)
    assertEquals("2026-11-20", ex.nextDueDateIso.value)
  }

  @Test
  fun handlesOcrTightCommaInIrregularDatesLookalikeGlyphsAndTwoRowScheduleCards() {
    val page = OcrDocumentPage(
      screenshotIndex = 0,
      lines = listOf(
        OcrLine("Maya Personal Loan", OcrBoundingBox(40, 40, 450, 72)),
        OcrLine("Remaining install\u0131ments", OcrBoundingBox(40, 100, 320, 128)),
        OcrLine("5", OcrBoundingBox(680, 100, 700, 128)),
        OcrLine("Loan Narme: Semestral Tuition Split | Ref #MY-2027", OcrBoundingBox(40, 140, 680, 168)),
        // Single-row irregular rows with ML Kit missing-space comma ("Feb 27,2027")
        OcrLine("Jan 5, 2027 | PHP 700.00 | Upcoming", OcrBoundingBox(40, 200, 700, 228)),
        OcrLine("Jan 19, 2027 | PHP 700.00 | Upcoming", OcrBoundingBox(40, 250, 700, 278)),
        OcrLine("Feb 2, 2027 | PHP 700.00 | Upcoming", OcrBoundingBox(40, 300, 700, 328)),
        OcrLine("Feb 27,2027 | PHP 700.00 | Upcoming", OcrBoundingBox(40, 350, 700, 378)),
        // Two-row card item: standalone date row followed by amount + status row
        OcrLine("Mar 19, 2027", OcrBoundingBox(40, 410, 240, 438)),
        OcrLine("PHP 700.00 | Upcoming", OcrBoundingBox(40, 450, 700, 478))
      )
    )

    val ex = DebtOcrExtractor.extract(page, refDate)

    assertEquals(5, ex.remainingInstallmentsCount.value)
    assertEquals("Semestral Tuition Split | Ref #MY-2027", ex.loanName.value)
    assertEquals(RepaymentFrequency.IRREGULAR, ex.frequency.value)
    assertEquals(
      listOf("2027-01-05", "2027-01-19", "2027-02-02", "2027-02-27", "2027-03-19"),
      ex.installmentRows.map { it.dueDateIso }
    )
    assertTrue(ex.installmentRows.all { it.amountCents == 70_000L })
  }
}
