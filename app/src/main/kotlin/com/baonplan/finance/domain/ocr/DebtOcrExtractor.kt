package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure Kotlin structured parser that extracts loan details and installment schedules
 * from one OCR document page. Preserves provenance and never fabricates missing data.
 */
object DebtOcrExtractor {

  private data class LenderRule(
    val canonicalLender: String,
    val defaultLoanType: String?,
    val patterns: List<Regex>
  )

  private val lenderRules = listOf(
    LenderRule("Shopee (SPayLater)", "BNPL", listOf(Regex("""\bspay\s*later\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Shopee (SLoan)", "Cash Loan", listOf(Regex("""\bsloan\b""", RegexOption.IGNORE_CASE))),
    LenderRule("BillEase", "Installment Loan", listOf(Regex("""\bbillease\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Atome", "BNPL", listOf(Regex("""\batome\b""", RegexOption.IGNORE_CASE))),
    LenderRule("GCash (GLoan)", "Cash Loan", listOf(Regex("""\bgloan\b""", RegexOption.IGNORE_CASE))),
    LenderRule("GCash (GGives)", "Installment Loan", listOf(Regex("""\bggives\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Maya (Maya Credit)", "Credit Line", listOf(Regex("""\bmaya\s+(?:easy\s+)?credit\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Maya (Personal Loan)", "Cash Loan", listOf(Regex("""\bmaya\s+personal\s+loan\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Lazada (LazPayLater)", "BNPL", listOf(Regex("""\blazpay\s*later\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Home Credit", "Installment Loan", listOf(Regex("""\bhome\s+credit\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Tala", "Cash Loan", listOf(Regex("""\btala\b""", RegexOption.IGNORE_CASE))),
    LenderRule("JuanHand", "Cash Loan", listOf(Regex("""\bjuan\s*hand\b""", RegexOption.IGNORE_CASE))),
    LenderRule("SeaBank", "Cash Loan", listOf(Regex("""\bseabank\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Tonik", "Cash Loan", listOf(Regex("""\btonik\b""", RegexOption.IGNORE_CASE))),
    LenderRule("CIMB", "Credit Line", listOf(Regex("""\bcimb\b|\brevict?\s*credit\b|\brevicredit\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Cashalo", "Cash Loan", listOf(Regex("""\bcashalo\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Digido", "Cash Loan", listOf(Regex("""\bdigido\b""", RegexOption.IGNORE_CASE))),
    LenderRule("GCash", null, listOf(Regex("""\bgcash\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Maya", null, listOf(Regex("""\bpaymaya\b|\bmaya\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Shopee", null, listOf(Regex("""\bshopee\b""", RegexOption.IGNORE_CASE))),
    LenderRule("Lazada", null, listOf(Regex("""\blazada\b""", RegexOption.IGNORE_CASE)))
  )

  private val prefixedPesoRegex = Regex(
    """(?:₱|PHP|Php|PhP|(?<![A-Za-z])P(?=\s*\d))\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?|[0-9]{1,3}(?:\.[0-9]{3})+(?:\.[0-9]{2}))(?![\d%])"""
  )

  // Bare decimal/grouped peso in labeled value segments only (must NOT match dates, %, or 4-digit years alone unless explicitly paired with label)
  private val bareLabeledMoneyRegex = Regex(
    """(?<![A-Za-z0-9./-])([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?|[0-9]+\.[0-9]{2}|[0-9]{2,6})(?![A-Za-z0-9%./-])"""
  )

  private val percentageRegex = Regex("""\d+(?:\.\d+)?\s*%""")

  private val paidFractionRegex = Regex(
    """(?:paid\s*[:\-]?\s*)?(\d{1,2})\s*(?:/|of)\s*(\d{1,2})\s*(paid|installments?|payments?|months?|mos\.?)?""",
    RegexOption.IGNORE_CASE
  )

  private val remainingFractionRegex = Regex(
    """(\d{1,2})\s*(?:/|of)\s*(\d{1,2})\s*(?:left|remaining|unpaid)""",
    RegexOption.IGNORE_CASE
  )

  private val refIdLabelRegex = Regex(
    """(?:loan\s*(?:id|no\.?|number|#)|reference\s*(?:id|no\.?|number|#)?|ref\.?\s*(?:id|no\.?|#)|order\s*(?:id|no\.?|#)|contract\s*(?:id|no\.?|#)|account\s*(?:id|no\.?|#))\s*[:\-]?\s*([A-Za-z0-9][A-Za-z0-9\-_/#]{2,28})""",
    RegexOption.IGNORE_CASE
  )

  private val inlineOrderHashRegex = Regex(
    """\b(?:order|loan|contract|ref)\s*#\s*([A-Za-z0-9\-_]{3,24})\b""",
    RegexOption.IGNORE_CASE
  )

  private val nonScheduleDateHeaderKeywords = listOf(
    "loan date", "date granted", "disbursed", "disbursement", "created",
    "generated", "approved", "printed", "as of", "issued", "application date",
    "start date", "applied on"
  )

  fun extract(
    page: OcrDocumentPage,
    referenceDate: LocalDate = ManilaTime.today()
  ): SingleScreenshotExtraction {
    val rows = OcrLayoutNormalizer.normalize(page)
    if (rows.isEmpty()) {
      return SingleScreenshotExtraction(
        screenshotIndex = page.screenshotIndex,
        documentKind = ExtractedDocumentKind.UNSTRUCTURED
      )
    }

    val rowTexts = rows.map { it.text }
    val numericOrder = FlexibleDateParser.inferNumericOrder(rowTexts)
    val docYears = FlexibleDateParser.extractDocumentYears(rowTexts)
    val contextYear = docYears.singleOrNull() ?: docYears.maxOrNull()

    val idx = page.screenshotIndex
    val issues = mutableListOf<ImportValidationIssue>()

    // 1. Lender, loan type, reference ID, loan name
    val lenderResult = extractLenderAndType(rows, idx)
    val lenderField = lenderResult.first
    var loanTypeField = lenderResult.second
    val refIdField = extractReferenceId(rows, idx)
    var loanNameField = extractLoanName(rows, idx, lenderField.value, refIdField.value)

    // 2. Monetary key-value fields (Strict semantic separation)
    var originalPrincipal = ExtractedField.notDetected<Long>()
    var amountReceived = ExtractedField.notDetected<Long>()
    var outstandingBalance = ExtractedField.notDetected<Long>()
    var remainingInstallmentTotal = ExtractedField.notDetected<Long>()
    var installmentAmount = ExtractedField.notDetected<Long>()
    var totalRepayment = ExtractedField.notDetected<Long>()
    var interest = ExtractedField.notDetected<Long>()
    var serviceFee = ExtractedField.notDetected<Long>()
    var processingFee = ExtractedField.notDetected<Long>()
    var otherFees = ExtractedField.notDetected<Long>()
    var otherFeeTotalAccumulator = 0L
    val otherFeeSnippets = mutableListOf<String>()

    var explicitOriginalCount = ExtractedField.notDetected<Int>()
    var explicitPaidCount = ExtractedField.notDetected<Int>()
    var explicitRemainingCount = ExtractedField.notDetected<Int>()

    var nextDueDate = ExtractedField.notDetected<String>()
    var finalDueDate = ExtractedField.notDetected<String>()
    var lastPaidDate = ExtractedField.notDetected<String>()

    var explicitFrequency = ExtractedField.notDetected<RepaymentFrequency>()
    var customIntervalDays = ExtractedField.notDetected<Int>()
    var loanStatus = ExtractedField.notDetected<DebtStatus>()

    for (i in rows.indices) {
      val row = rows[i]
      val text = row.text
      val lower = canonicalizeKeywords(text)
      val nextRow = rows.getOrNull(i + 1)

      // Check fraction badges ("2/6 paid", "Paid 2 of 6", "4/6 remaining")
      extractCountFractions(
        text = text,
        screenshotIndex = idx,
        currentOrig = explicitOriginalCount,
        currentPaid = explicitPaidCount,
        currentRem = explicitRemainingCount
      )?.let { (o, p, r) ->
        if (explicitOriginalCount.value == null && o.value != null) explicitOriginalCount = o
        if (explicitPaidCount.value == null && p.value != null) explicitPaidCount = p
        if (explicitRemainingCount.value == null && r.value != null) explicitRemainingCount = r
      }

      // Check loan status cues
      if (loanStatus.value == null) {
        when {
          containsAny(lower, "fully paid", "paid in full", "loan settled", "status: paid", "status | paid") ->
            loanStatus = ExtractedField.extracted(DebtStatus.PAID, text, idx)
          containsAny(lower, "overdue", "past due", "active", "ongoing", "in repayment") &&
            !containsAny(lower, "if overdue", "late fee") ->
            loanStatus = ExtractedField.extracted(DebtStatus.ACTIVE, text, idx)
        }
      }

      // Check explicit frequency / custom interval text
      if (explicitFrequency.value == null && !lower.contains("interest") && !lower.contains("fee")) {
        parseExplicitFrequency(text, idx)?.let { (freq, days) ->
          explicitFrequency = freq
          if (days != null && customIntervalDays.value == null) {
            customIntervalDays = ExtractedField.extracted(days, text, idx)
          }
        }
      }

      // Check loan type override if explicit label present
      if (containsAny(lower, "loan type", "product type", "plan type", "facility")) {
        val afterColon = extractRightSideText(row)
        if (!afterColon.isNullOrBlank()) {
          loanTypeField = ExtractedField.extracted(afterColon, text, idx)
        }
      }

      // Skip table rows that look primarily like installment rows when scanning summary scalars,
      // unless they carry a very clear summary label like "Next payment" or "Installment amount".
      val isLikelyScheduleRow = looksLikeScheduleRow(row, numericOrder, contextYear, referenceDate)

      // Helper to resolve monetary value either from right column/same row or vertical next row (when row has no money token)
      fun resolveMoneyForLabel(): Pair<MoneyMatch?, String> {
        val sameRow = extractBestAmountFromRow(row, skipLeftLabel = true)
        if (sameRow != null) return sameRow to text
        if (nextRow != null && isStandaloneValueRow(nextRow) && !looksLikeFinancialLabel(nextRow.text)) {
          val below = extractBestAmountFromRow(nextRow, skipLeftLabel = false)
          if (below != null) return below to "$text -> ${nextRow.text}"
        }
        return null to text
      }

      // Explicit dates on labeled summary lines
      if (!isLikelyScheduleRow || containsAny(lower, "next payment", "next due", "due date", "final payment", "maturity", "last payment")) {
        fun resolveDateForLabel(): Pair<ParsedDateMatch?, String> {
          val dSame = FlexibleDateParser.parseSingleDate(text, numericOrder, contextYear, referenceDate)
          if (dSame != null) return dSame to text
          if (nextRow != null && !looksLikeFinancialLabel(nextRow.text)) {
            val dBelow = FlexibleDateParser.parseSingleDate(nextRow.text, numericOrder, contextYear, referenceDate)
            if (dBelow != null) return dBelow to "$text -> ${nextRow.text}"
          }
          return null to text
        }

        if (nextDueDate.value == null &&
          containsAny(lower, "next payment due", "next due date", "next payment date", "next payment", "next due", "payment due date", "due on", "due date") &&
          !containsAny(lower, "final", "last payment", "maturity", "end date", "first due")
        ) {
          val (d, src) = resolveDateForLabel()
          if (d != null) {
            nextDueDate = ExtractedField.extracted(d.isoDate, src, idx, d.confidence, d.note)
          }
        }

        if (finalDueDate.value == null &&
          containsAny(lower, "final payment", "final due", "last due date", "maturity date", "end date", "last installment date")
        ) {
          val (d, src) = resolveDateForLabel()
          if (d != null) {
            finalDueDate = ExtractedField.extracted(d.isoDate, src, idx, d.confidence, d.note)
          }
        }

        if (lastPaidDate.value == null &&
          containsAny(lower, "last paid", "last payment made", "latest payment", "last settled")
        ) {
          val (d, src) = resolveDateForLabel()
          if (d != null) {
            lastPaidDate = ExtractedField.extracted(d.isoDate, src, idx, d.confidence, d.note)
          }
        }
      }

      // Numeric counts via explicit labels
      if (explicitOriginalCount.value == null &&
        containsAny(lower, "original installments", "total installments", "number of installments", "total payments", "installment count", "loan term", "tenor")
      ) {
        val count = extractIntegerValueNearLabel(row, nextRow)
        if (count != null && count in 1..120) {
          val isTenorMonths = containsAny(lower, "tenor", "loan term") && lower.contains("month")
          explicitOriginalCount = ExtractedField.extracted(
            value = count,
            sourceSnippet = text,
            screenshotIndex = idx,
            confidence = if (isTenorMonths) ExtractionConfidence.NEEDS_VERIFICATION else ExtractionConfidence.HIGH,
            verificationReason = if (isTenorMonths) "Extracted from tenor/months label — confirm installment count" else null
          )
        }
      }
      if (explicitPaidCount.value == null &&
        containsAny(lower, "installments paid", "paid installments", "payments made", "already paid count", "completed installments")
      ) {
        val count = extractIntegerValueNearLabel(row, nextRow)
        if (count != null && count in 0..120) {
          explicitPaidCount = ExtractedField.extracted(count, text, idx)
        }
      }
      if (explicitRemainingCount.value == null &&
        containsAny(lower, "remaining installments", "installments left", "payments left", "unpaid installments", "remaining payments") &&
        !containsAny(lower, "total")
      ) {
        val count = extractIntegerValueNearLabel(row, nextRow)
        if (count != null && count in 0..120) {
          explicitRemainingCount = ExtractedField.extracted(count, text, idx)
        }
      }

      // Financial labels — MUST remain strictly separated!
      // 2a. Remaining Installment Total (distinct from Balance!)
      if (remainingInstallmentTotal.value == null &&
        containsAny(
          lower,
          "remaining installment total",
          "remaining installments total",
          "total remaining installments",
          "total unpaid installments",
          "remaining scheduled total",
          "unpaid installment total",
          "installments total left"
        )
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          remainingInstallmentTotal = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2b. Outstanding / Current Balance (distinct from Remaining Installment Total!)
      if (outstandingBalance.value == null &&
        containsAny(
          lower,
          "current outstanding balance",
          "outstanding balance",
          "remaining balance",
          "current balance",
          "principal balance",
          "unpaid balance",
          "loan balance",
          "balance remaining",
          "remaining principal",
          "remaining due"
        ) || (isStandaloneBalanceLabel(row) && outstandingBalance.value == null)
      ) {
        if (!containsAny(lower, "available balance", "wallet balance", "credit limit", "installment total")) {
          val (m, src) = resolveMoneyForLabel()
          if (m != null) {
            outstandingBalance = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
          }
          continue
        }
      }

      // 2c. Original Loan Amount / Principal
      if (originalPrincipal.value == null &&
        containsAny(
          lower,
          "original loan amount",
          "original amount",
          "loan amount",
          "principal amount",
          "amount borrowed",
          "financed amount",
          "credit availed",
          "borrowed amount",
          "principal"
        ) &&
        !containsAny(lower, "balance", "remaining")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          originalPrincipal = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2d. Amount Received / Net Disbursed
      if (amountReceived.value == null &&
        containsAny(lower, "amount received", "net proceeds", "net disbursed", "disbursed amount", "cash received", "received amount")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          amountReceived = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2e. Total Repayment / Total Payable
      if (totalRepayment.value == null &&
        containsAny(lower, "total repayment", "total scheduled repayment", "total payable", "total amount payable", "total scheduled", "total loan cost")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          totalRepayment = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2f. Installment Amount
      if (installmentAmount.value == null &&
        !isLikelyScheduleRow &&
        containsAny(
          lower,
          "installment amount",
          "monthly installment",
          "weekly installment",
          "biweekly installment",
          "bi-weekly installment",
          "next installment",
          "installment due",
          "amortization",
          "per installment",
          "per payment",
          "each payment",
          "monthly due",
          "weekly due",
          "monthly payment",
          "weekly payment",
          "repayment amount"
        )
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          installmentAmount = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2g. Interest (skip percentages!)
      if (interest.value == null &&
        containsAny(lower, "total interest", "interest fee", "interest amount", "interest charge", "interest") &&
        !containsAny(lower, "interest rate", "monthly rate", "eir", "apr")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          interest = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2h. Service Fee
      if (serviceFee.value == null &&
        containsAny(lower, "service fee", "service charge", "platform fee")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          serviceFee = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2i. Processing Fee
      if (processingFee.value == null &&
        containsAny(lower, "processing fee", "origination fee", "disbursement fee")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          processingFee = ExtractedField.extracted(m.cents, src, idx, m.confidence, m.note)
        }
        continue
      }

      // 2j. Other Fees (late fee, admin fee, DST, convenience fee, other fees)
      if (containsAny(lower, "other fee", "other fees", "admin fee", "late fee", "penalty fee", "convenience fee", "doc stamp", "documentary stamp", "insurance fee") &&
        !containsAny(lower, "if paid late", "up to ")
      ) {
        val (m, src) = resolveMoneyForLabel()
        if (m != null) {
          otherFeeTotalAccumulator += m.cents
          otherFeeSnippets.add(src)
          otherFees = ExtractedField.extracted(
            value = otherFeeTotalAccumulator,
            sourceSnippet = otherFeeSnippets.joinToString("; "),
            screenshotIndex = idx,
            confidence = if (otherFeeSnippets.size > 1) ExtractionConfidence.NEEDS_VERIFICATION else m.confidence,
            verificationReason = m.note
          )
        }
      }
    }

    // Check explicit conflict between Balance and Remaining Installment Total on the same screenshot
    val balVal = outstandingBalance.value
    val remTotalVal = remainingInstallmentTotal.value
    if (balVal != null && remTotalVal != null && kotlin.math.abs(balVal - remTotalVal) > 100L) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "remainingBalanceCents",
          severity = ImportSeverity.WARNING,
          title = "Balance differs from Remaining Installment Total",
          message = "Screenshot #${idx + 1} shows Balance (${MoneyFormatter.formatPeso(balVal)}) and Remaining Installment Total (${MoneyFormatter.formatPeso(remTotalVal)}) separately — please confirm which reflects your true remaining obligation."
        )
      )
    }

    // 3. Extract repayment schedule rows in order
    val extractedRows = extractScheduleRows(rows, numericOrder, contextYear, referenceDate, idx)

    // Derive conservative summary fields from extracted schedule rows if scalars weren't directly labeled
    val unpaidRows = extractedRows.filter {
      it.status == ExtractedInstallmentStatus.UPCOMING ||
        it.status == ExtractedInstallmentStatus.OVERDUE ||
        it.status == ExtractedInstallmentStatus.UNKNOWN
    }
    val paidRows = extractedRows.filter { it.status == ExtractedInstallmentStatus.PAID }

    if (nextDueDate.value == null) {
      val firstUnpaidWithDate = unpaidRows.firstOrNull { it.dueDateIso != null }
      if (firstUnpaidWithDate != null) {
        nextDueDate = ExtractedField.extracted(
          value = firstUnpaidWithDate.dueDateIso!!,
          sourceSnippet = firstUnpaidWithDate.sourceSnippet,
          screenshotIndex = idx,
          confidence = firstUnpaidWithDate.dateConfidence,
          verificationReason = firstUnpaidWithDate.verificationNote
        )
      }
    }

    if (finalDueDate.value == null && extractedRows.size >= 2) {
      val lastRowWithDate = extractedRows.lastOrNull { it.dueDateIso != null }
      if (lastRowWithDate != null && lastRowWithDate.dueDateIso != nextDueDate.value) {
        finalDueDate = ExtractedField.extracted(
          value = lastRowWithDate.dueDateIso!!,
          sourceSnippet = lastRowWithDate.sourceSnippet,
          screenshotIndex = idx,
          confidence = lastRowWithDate.dateConfidence,
          verificationReason = lastRowWithDate.verificationNote
        )
      }
    }

    if (lastPaidDate.value == null && paidRows.isNotEmpty()) {
      val latestPaid = paidRows.lastOrNull { it.dueDateIso != null }
      if (latestPaid != null) {
        lastPaidDate = ExtractedField.extracted(
          value = latestPaid.dueDateIso!!,
          sourceSnippet = latestPaid.sourceSnippet,
          screenshotIndex = idx,
          confidence = latestPaid.dateConfidence,
          verificationReason = latestPaid.verificationNote
        )
      }
    }

    // Derive installment amount ONLY if consistent across rows and not explicitly labeled
    if (installmentAmount.value == null) {
      val rowAmounts = extractedRows.mapNotNull { it.amountCents }.filter { it > 0L }.distinct()
      if (rowAmounts.size == 1 && extractedRows.size >= 2) {
        installmentAmount = ExtractedField.extracted(
          value = rowAmounts.single(),
          sourceSnippet = extractedRows.first { it.amountCents == rowAmounts.single() }.sourceSnippet,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH
        )
      }
    }

    // 4. Infer frequency ONLY when strict evidence rules pass (never guess from < 3 dates!)
    val finalFrequencyAndDays = if (explicitFrequency.value != null) {
      explicitFrequency to customIntervalDays
    } else {
      inferFrequencyFromRows(
        rows = extractedRows,
        explicitOriginalCount = explicitOriginalCount.value,
        screenshotIndex = idx
      )
    }
    val finalFrequency = finalFrequencyAndDays.first
    val finalCustomDays = finalFrequencyAndDays.second

    // Derive loan status from rows when explicit status wasn't shown
    if (loanStatus.value == null) {
      loanStatus = when {
        extractedRows.isNotEmpty() && unpaidRows.isEmpty() && paidRows.isNotEmpty() ->
          ExtractedField.extracted(DebtStatus.PAID, paidRows.last().sourceSnippet, idx, ExtractionConfidence.NEEDS_VERIFICATION, "All detected schedule rows appear paid")
        outstandingBalance.value == 0L ->
          ExtractedField.extracted(DebtStatus.PAID, outstandingBalance.sourceSnippet, idx)
        else -> ExtractedField.notDetected()
      }
    }

    val kind = classifyDocumentKind(
      hasSummaryScalars = originalPrincipal.value != null || outstandingBalance.value != null ||
        interest.value != null || serviceFee.value != null || totalRepayment.value != null,
      scheduleRowCount = extractedRows.size
    )

    if (loanNameField.value.isNullOrBlank() && !lenderField.value.isNullOrBlank()) {
      val suggestion = buildString {
        append(lenderField.value)
        if (!refIdField.value.isNullOrBlank()) {
          append(" #").append(refIdField.value)
        } else if (!loanTypeField.value.isNullOrBlank()) {
          append(" ").append(loanTypeField.value)
        }
      }
      loanNameField = ExtractedField.calculated(
        value = suggestion,
        sourceSnippet = lenderField.sourceSnippet,
        screenshotIndex = idx,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Suggested from detected lender — rename if you have multiple loans"
      )
    }

    return SingleScreenshotExtraction(
      screenshotIndex = idx,
      documentKind = kind,
      lender = lenderField,
      loanName = loanNameField,
      loanType = loanTypeField,
      loanReferenceId = refIdField,
      originalPrincipalCents = originalPrincipal,
      amountReceivedCents = amountReceived,
      outstandingBalanceCents = outstandingBalance,
      remainingInstallmentTotalCents = remainingInstallmentTotal,
      installmentAmountCents = installmentAmount,
      originalInstallmentCount = explicitOriginalCount,
      paidInstallmentsCount = explicitPaidCount,
      remainingInstallmentsCount = explicitRemainingCount,
      frequency = finalFrequency,
      customIntervalDays = finalCustomDays,
      nextDueDateIso = nextDueDate,
      finalDueDateIso = finalDueDate,
      lastPaidDateIso = lastPaidDate,
      totalRepaymentCents = totalRepayment,
      interestCents = interest,
      serviceFeeCents = serviceFee,
      processingFeeCents = processingFee,
      otherFeesCents = otherFees,
      loanStatus = loanStatus,
      installmentRows = extractedRows,
      issues = issues
    )
  }

  private fun extractLenderAndType(
    rows: List<NormalizedOcrRow>,
    idx: Int
  ): Pair<ExtractedField<String>, ExtractedField<String>> {
    for (row in rows) {
      for (rule in lenderRules) {
        if (rule.patterns.any { it.containsMatchIn(row.text) }) {
          val lender = ExtractedField.extracted(rule.canonicalLender, row.text, idx)
          val type = if (rule.defaultLoanType != null) {
            ExtractedField.extracted(rule.defaultLoanType, row.text, idx)
          } else {
            ExtractedField.notDetected()
          }
          return lender to type
        }
      }
    }
    for (row in rows) {
      val lower = row.text.lowercase(Locale.US)
      if (containsAny(lower, "lender", "provider", "finance company", "bank name", "merchant", "platform")) {
        val right = extractRightSideText(row)
        if (!right.isNullOrBlank() && right.length in 2..40 && !right.all { it.isDigit() }) {
          return ExtractedField.extracted(right, row.text, idx) to ExtractedField.notDetected()
        }
      }
    }
    return ExtractedField.notDetected<String>() to ExtractedField.notDetected()
  }

  private fun extractReferenceId(rows: List<NormalizedOcrRow>, idx: Int): ExtractedField<String> {
    for (row in rows) {
      val match = refIdLabelRegex.find(row.text)
      if (match != null) {
        val code = match.groupValues[1].trim().trimEnd('.', ',', ';')
        if (code.length >= 3 && code.any { it.isDigit() }) {
          return ExtractedField.extracted(code, row.text, idx)
        }
      }
      val hashMatch = inlineOrderHashRegex.find(row.text)
      if (hashMatch != null) {
        val code = hashMatch.groupValues[1].trim()
        if (code.length >= 3) {
          return ExtractedField.extracted(code, row.text, idx)
        }
      }
    }
    return ExtractedField.notDetected()
  }

  private fun extractLoanName(
    rows: List<NormalizedOcrRow>,
    idx: Int,
    detectedLender: String?,
    detectedRefId: String?
  ): ExtractedField<String> {
    for (row in rows) {
      val lower = canonicalizeKeywords(row.text)
      if (containsAny(lower, "loan name", "product name", "item name", "item description", "loan purpose", "purchase item", "product:", "plan name")) {
        val right = extractRightSideText(row)
        if (!right.isNullOrBlank() && right.length in 2..50 && !looksOnlyCurrencyOrDate(right)) {
          return ExtractedField.extracted(right, row.text, idx)
        }
      }
    }
    // Check top 3 header rows for descriptive title that contains neither just lender nor pure money/date
    for (row in rows.take(4)) {
      val cleaned = row.columns.firstOrNull()?.trim().orEmpty()
      val lower = cleaned.lowercase(Locale.US)
      if (cleaned.length in 4..45 &&
        cleaned.any { it.isLetter() } &&
        !looksOnlyCurrencyOrDate(cleaned) &&
        !containsAny(lower, "balance", "payment schedule", "repayment schedule", "loan details", "installment plan", "summary", "transaction", "status", "details") &&
        (detectedLender == null || !cleaned.equals(detectedLender, ignoreCase = true)) &&
        cleaned.contains(Regex("""\b(loan|order|installment|credit|cash|phone|laptop|tuition|bag|shoes|bill)\b""", RegexOption.IGNORE_CASE))
      ) {
        val suffix = detectedRefId?.let { if (!cleaned.contains(it, ignoreCase = true)) " #$it" else "" } ?: ""
        return ExtractedField.extracted(
          value = (cleaned + suffix).take(50),
          sourceSnippet = row.text,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.NEEDS_VERIFICATION,
          verificationReason = "Read from header — confirm loan name"
        )
      }
    }
    return ExtractedField.notDetected()
  }

  private fun extractCountFractions(
    text: String,
    screenshotIndex: Int,
    currentOrig: ExtractedField<Int>,
    currentPaid: ExtractedField<Int>,
    currentRem: ExtractedField<Int>
  ): Triple<ExtractedField<Int>, ExtractedField<Int>, ExtractedField<Int>>? {
    // Reject calendar dates like 11/16/2026 or 12/25
    val cleanedText = percentageRegex.replace(text, " ")
    val lower = cleanedText.lowercase(Locale.US)

    remainingFractionRegex.find(cleanedText)?.let { m ->
      val rem = m.groupValues[1].toIntOrNull()
      val orig = m.groupValues[2].toIntOrNull()
      if (rem != null && orig != null && orig in 1..120 && rem in 0..orig) {
        return Triple(
          if (currentOrig.value == null) ExtractedField.extracted(orig, text, screenshotIndex) else currentOrig,
          currentPaid,
          if (currentRem.value == null) ExtractedField.extracted(rem, text, screenshotIndex) else currentRem
        )
      }
    }

    for (m in paidFractionRegex.findAll(cleanedText)) {
      // Make sure slash isn't part of a mm/dd/yyyy date
      val afterIdx = m.range.last + 1
      if (afterIdx < cleanedText.length && cleanedText[afterIdx] == '/') continue
      val a = m.groupValues[1].toIntOrNull() ?: continue
      val b = m.groupValues[2].toIntOrNull() ?: continue
      val unitHint = m.groupValues[3]
      val hasCountContext = unitHint.isNotBlank() ||
        containsAny(lower, "paid", "installment", "repayment", "tenor", "term", "progress")
      if (!hasCountContext) continue
      if (b !in 1..120 || a !in 0..b) continue

      val isRemainingPhrase = lower.contains("remaining") || lower.contains("left") || lower.contains("unpaid")
      if (isRemainingPhrase) {
        return Triple(
          if (currentOrig.value == null) ExtractedField.extracted(b, text, screenshotIndex) else currentOrig,
          currentPaid,
          if (currentRem.value == null) ExtractedField.extracted(a, text, screenshotIndex) else currentRem
        )
      } else {
        return Triple(
          if (currentOrig.value == null) ExtractedField.extracted(b, text, screenshotIndex) else currentOrig,
          if (currentPaid.value == null) ExtractedField.extracted(a, text, screenshotIndex) else currentPaid,
          currentRem
        )
      }
    }
    return null
  }

  private fun parseExplicitFrequency(
    text: String,
    idx: Int
  ): Pair<ExtractedField<RepaymentFrequency>, Int?>? {
    val lower = text.lowercase(Locale.US)
    val everyDays = Regex("""every\s+(\d{1,3})\s+days?""", RegexOption.IGNORE_CASE).find(lower)
    if (everyDays != null) {
      val days = everyDays.groupValues[1].toIntOrNull()
      if (days != null && days in 1..365) {
        return when (days) {
          7 -> ExtractedField.extracted(RepaymentFrequency.WEEKLY, text, idx) to null
          14 -> ExtractedField.extracted(RepaymentFrequency.EVERY_14_DAYS, text, idx) to null
          else -> ExtractedField.extracted(RepaymentFrequency.CUSTOM_DAYS, text, idx) to days
        }
      }
    }
    return when {
      Regex("""\bsemi[- ]?monthly\b|\btwice\s+a\s+month\b|\b15th\s*(?:&|and)\s*30th\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.IRREGULAR, text, idx) to null
      Regex("""\bbi[- ]?weekly\b|\bevery\s+(?:two|2)\s+weeks\b|\bfortnightly\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.EVERY_14_DAYS, text, idx) to null
      Regex("""\bweekly\b|\bevery\s+week\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.WEEKLY, text, idx) to null
      Regex("""\bmonthly\b|\bevery\s+month\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.MONTHLY, text, idx) to null
      Regex("""\bone[- ]?time\b|\bsingle\s+payment\b|\blump\s+sum\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.ONE_TIME, text, idx) to null
      Regex("""\birregular\b|\bcustom\s+schedule\b|\bflexible\s+dates?\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.IRREGULAR, text, idx) to null
      else -> null
    }
  }

  private fun extractScheduleRows(
    rows: List<NormalizedOcrRow>,
    numericOrder: NumericDateOrder,
    contextYear: Int?,
    referenceDate: LocalDate,
    screenshotIndex: Int
  ): List<ExtractedInstallmentRow> {
    val result = mutableListOf<ExtractedInstallmentRow>()
    var i = 0
    while (i < rows.size) {
      val row = rows[i]
      val lower = row.text.lowercase(Locale.US)
      val dateMatch = FlexibleDateParser.parseSingleDate(row.text, numericOrder, contextYear, referenceDate)

      if (dateMatch != null) {
        val isNonScheduleMetadata = nonScheduleDateHeaderKeywords.any { lower.contains(it) } ||
          containsAny(
            lower,
            "next payment due", "next due date", "next payment date", "next due:", "next due |",
            "due date:", "due date |", "payment due date", "due on:", "due on |",
            "final payment", "maturity date", "last paid", "last payment made"
          )
        val nextRow = rows.getOrNull(i + 1)
        val nextRowDate = nextRow?.let {
          FlexibleDateParser.parseSingleDate(it.text, numericOrder, contextYear, referenceDate)
        }
        val nextRowUsableForCard = nextRow != null &&
          nextRowDate == null &&
          !looksLikeFinancialLabel(nextRow.text)
        val nextRowMoney = if (nextRowUsableForCard) {
          extractBestMoneyFromCleanText(nextRow.text, allowBare = true)
        } else {
          null
        }
        val nextRowHasStatus = nextRowUsableForCard &&
          hasExplicitScheduleStatus(nextRow.text.lowercase(Locale.US))

        val hasScheduleSignal = hasExplicitScheduleStatus(lower) ||
          hasInstallmentOrdinal(row.text) ||
          (row.columns.size >= 2 && row.columns.any { col -> extractBestMoneyFromCleanText(col, allowBare = true) != null }) ||
          nextRowMoney != null ||
          nextRowHasStatus

        if (!isNonScheduleMetadata && hasScheduleSignal) {
          // Strip parsed date text and leading ordinal ("#10 |", "10.") before scanning peso amounts
          val rowWithoutDate = row.text
            .replace(dateMatch.rawText, " ")
            .replace(Regex("""^\s*(?:#?\d{1,2}(?:st|nd|rd|th)?\s*(?:\||\.|-|:)\s*|#?\d{1,2}\s+)""", RegexOption.IGNORE_CASE), " ")
          var moneyMatch = extractBestMoneyFromCleanText(rowWithoutDate, allowBare = true)
          var snippet = row.text
          var combinedStatusText = lower

          // Support 2-row card schedule item: Date (+ optional Status) on row i, Amount / Status on row i+1
          if (moneyMatch == null && nextRowUsableForCard && (nextRowMoney != null || nextRowHasStatus)) {
            moneyMatch = nextRowMoney
            snippet = "${row.text} | ${nextRow.text}"
            combinedStatusText = "$combinedStatusText ${nextRow.text.lowercase(Locale.US)}"
            i++
          }

          val status = parseInstallmentStatus(combinedStatusText)
          val numberHint = extractInstallmentOrdinal(row.text)
          val rowId = "row_${screenshotIndex}_${result.size}_${dateMatch.isoDate}"

          if (moneyMatch != null || hasExplicitScheduleStatus(combinedStatusText) || numberHint != null) {
            val dueStatus = when {
              dateMatch.confidence == ExtractionConfidence.HIGH -> DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
              else -> DueDateStatus.UNCONFIRMED
            }
            result.add(
              ExtractedInstallmentRow(
                id = rowId,
                installmentNumberHint = numberHint,
                dueDateIso = dateMatch.isoDate,
                rawDateText = dateMatch.rawText,
                dateStatus = dueStatus,
                dateConfidence = dateMatch.confidence,
                amountCents = moneyMatch?.cents,
                status = status,
                sourceSnippet = snippet,
                screenshotIndex = screenshotIndex,
                verificationNote = listOfNotNull(dateMatch.note, moneyMatch?.note).joinToString("; ").ifBlank { null }
              )
            )
          }
        }
      } else if (hasInstallmentOrdinal(row.text) && !looksLikeFinancialLabel(row.text)) {
        // Installment row with missing or unreadable date (e.g. "#3 | ₱500.00 | Upcoming")
        val money = extractBestMoneyFromCleanText(row.text, allowBare = false)
        val numberHint = extractInstallmentOrdinal(row.text)
        if (money != null && (hasExplicitScheduleStatus(lower) || numberHint != null)) {
          result.add(
            ExtractedInstallmentRow(
              id = "row_${screenshotIndex}_${result.size}_nodate",
              installmentNumberHint = numberHint,
              dueDateIso = null,
              rawDateText = "",
              dateStatus = DueDateStatus.UNKNOWN,
              dateConfidence = ExtractionConfidence.NEEDS_VERIFICATION,
              amountCents = money.cents,
              status = parseInstallmentStatus(lower),
              sourceSnippet = row.text,
              screenshotIndex = screenshotIndex,
              verificationNote = "Due date missing in screenshot — confirm before relying on schedule"
            )
          )
        }
      }
      i++
    }

    // Deduplicate within same page
    return result.distinctBy { Triple(it.dueDateIso ?: it.rawDateText, it.amountCents, it.installmentNumberHint) }
  }

  private fun inferFrequencyFromRows(
    rows: List<ExtractedInstallmentRow>,
    explicitOriginalCount: Int?,
    screenshotIndex: Int
  ): Pair<ExtractedField<RepaymentFrequency>, ExtractedField<Int>> {
    val datedRows = rows.mapNotNull { r -> ManilaTime.parseDate(r.dueDateIso)?.let { r to it } }
      .distinctBy { it.second }
      .sortedBy { it.second }

    if (datedRows.size == 1 && explicitOriginalCount == 1) {
      return ExtractedField.extracted(
        value = RepaymentFrequency.ONE_TIME,
        sourceSnippet = datedRows.first().first.sourceSnippet,
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Single installment count detected — confirm schedule frequency"
      ) to ExtractedField.notDetected()
    }

    // Never assume weekly, biweekly, or monthly from < 3 dated rows alone!
    if (datedRows.size < 3) {
      return ExtractedField.notDetected<RepaymentFrequency>() to ExtractedField.notDetected()
    }

    val dates = datedRows.map { it.second }
    val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
    val snippet = datedRows.take(3).joinToString(" -> ") { ManilaTime.formatShort(ManilaTime.formatIso(it.second)) }

    if (gaps.all { it == 7 }) {
      return ExtractedField.extracted(
        value = RepaymentFrequency.WEEKLY,
        sourceSnippet = snippet,
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Inferred 7-day spacing across ${dates.size} dates — confirm frequency"
      ) to ExtractedField.notDetected()
    }

    if (gaps.all { it == 14 }) {
      return ExtractedField.extracted(
        value = RepaymentFrequency.EVERY_14_DAYS,
        sourceSnippet = snippet,
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Inferred 14-day spacing across ${dates.size} dates — confirm frequency"
      ) to ExtractedField.notDetected()
    }

    val isMonthly = dates.zipWithNext().all { (a, b) -> a.plusMonths(1) == b } ||
      (dates.zipWithNext().all { (a, b) ->
        b.year * 12 + b.monthValue - (a.year * 12 + a.monthValue) == 1 &&
          (a.dayOfMonth == b.dayOfMonth || (a.dayOfMonth == a.lengthOfMonth() && b.dayOfMonth == b.lengthOfMonth()))
      })
    if (isMonthly) {
      return ExtractedField.extracted(
        value = RepaymentFrequency.MONTHLY,
        sourceSnippet = snippet,
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Inferred monthly spacing across ${dates.size} dates — confirm frequency"
      ) to ExtractedField.notDetected()
    }

    val uniqueGaps = gaps.distinct()
    if (uniqueGaps.size == 1 && uniqueGaps.single() in 1..180) {
      val n = uniqueGaps.single()
      return ExtractedField.extracted(
        value = RepaymentFrequency.CUSTOM_DAYS,
        sourceSnippet = snippet,
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Inferred $n-day interval across ${dates.size} dates — confirm frequency"
      ) to ExtractedField.extracted(n, snippet, screenshotIndex, ExtractionConfidence.NEEDS_VERIFICATION)
    }

    return ExtractedField.extracted(
      value = RepaymentFrequency.IRREGULAR,
      sourceSnippet = snippet,
      screenshotIndex = screenshotIndex,
      confidence = ExtractionConfidence.HIGH,
      verificationReason = "Irregular intervals preserved (${gaps.joinToString("d, ")}d)"
    ) to ExtractedField.notDetected()
  }

  internal data class MoneyMatch(
    val cents: Long,
    val rawText: String,
    val confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
    val note: String? = null
  )

  private fun extractBestAmountFromRow(row: NormalizedOcrRow, skipLeftLabel: Boolean): MoneyMatch? {
    val candidates = if (skipLeftLabel && row.columns.size >= 2) {
      row.columns.drop(1) + listOf(row.text)
    } else {
      listOf(row.text)
    }
    for (segment in candidates) {
      val allowBare = row.columns.size >= 2 || segment.contains(':') || segment.contains('=')
      val match = extractBestMoneyFromCleanText(segment, allowBare = allowBare)
      if (match != null) return match
    }
    return null
  }

  private val bareDecimalMoneyRegex = Regex(
    """(?<![A-Za-z0-9./-])([0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{1,2}|[0-9]+\.[0-9]{2})(?![A-Za-z0-9%./-])"""
  )

  private fun extractBestMoneyFromCleanText(text: String, allowBare: Boolean): MoneyMatch? {
    val cleaned = percentageRegex.replace(text, " ")
    for (m in prefixedPesoRegex.findAll(cleaned)) {
      parsePesoTokenSafely(m.groupValues[1], hasCurrencyPrefix = true)?.let { return it }
    }
    if (allowBare) {
      val stripped = paidFractionRegex.replace(cleaned, " ")
      for (m in bareDecimalMoneyRegex.findAll(stripped)) {
        parsePesoTokenSafely(m.groupValues[1], hasCurrencyPrefix = false)?.let { return it }
      }
      for (m in bareLabeledMoneyRegex.findAll(stripped)) {
        val token = m.groupValues[1]
        val intVal = token.toLongOrNull()
        if (intVal != null && intVal in 2020L..2035L && !token.contains('.')) continue
        parsePesoTokenSafely(token, hasCurrencyPrefix = false)?.let { return it }
      }
    }
    return null
  }

  private fun parsePesoTokenSafely(rawToken: String, hasCurrencyPrefix: Boolean): MoneyMatch? {
    // Handle OCR thousand separator misread: "1.250.00" -> "1,250.00"
    val dotParts = rawToken.split('.')
    if (dotParts.size >= 3 && dotParts.last().length == 2) {
      val repaired = dotParts.dropLast(1).joinToString(",") + "." + dotParts.last()
      MoneyFormatter.parsePesoToCents(repaired)?.let { cents ->
        return MoneyMatch(
          cents = cents,
          rawText = rawToken,
          confidence = ExtractionConfidence.NEEDS_VERIFICATION,
          note = "Normalized OCR punctuation in \"$rawToken\" — verify amount"
        )
      }
    }
    val cents = MoneyFormatter.parsePesoToCents(rawToken) ?: return null
    val conf = if (hasCurrencyPrefix || rawToken.contains('.') || rawToken.contains(',')) {
      ExtractionConfidence.HIGH
    } else {
      ExtractionConfidence.NEEDS_VERIFICATION
    }
    return MoneyMatch(cents = cents, rawText = rawToken, confidence = conf)
  }

  private fun extractIntegerValueNearLabel(row: NormalizedOcrRow, nextRow: NormalizedOcrRow?): Int? {
    val right = extractRightSideText(row)
    val matchRight = right?.let { Regex("""\b(\d{1,3})\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    if (matchRight != null) return matchRight

    val sameRowNum = Regex("""[:\-|]\s*(\d{1,3})\b|\b(\d{1,3})\s*(?:months?|mos\.?|installments?|payments?|left)\b""", RegexOption.IGNORE_CASE)
      .find(row.text)
      ?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.toIntOrNull() }
    if (sameRowNum != null) return sameRowNum

    if (nextRow != null && isStandaloneValueRow(nextRow) && !looksLikeFinancialLabel(nextRow.text)) {
      return Regex("""^\s*(\d{1,3})\s*(?:months?|mos\.?|installments?|payments?)?\s*$""", RegexOption.IGNORE_CASE)
        .find(nextRow.text)
        ?.groupValues?.get(1)?.toIntOrNull()
    }
    return null
  }

  private fun extractRightSideText(row: NormalizedOcrRow): String? {
    if (row.columns.size >= 2) {
      return row.columns.drop(1).joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }
    val colonIdx = row.text.indexOf(':')
    if (colonIdx in 1 until row.text.lastIndex) {
      return row.text.substring(colonIdx + 1).trim().takeIf { it.isNotEmpty() }
    }
    return null
  }

  private fun isStandaloneValueRow(row: NormalizedOcrRow): Boolean =
    row.columns.size <= 1 && (prefixedPesoRegex.containsMatchIn(row.text) ||
      bareLabeledMoneyRegex.containsMatchIn(row.text) ||
      FlexibleDateParser.parseSingleDate(row.text) != null)

  private fun isStandaloneBalanceLabel(row: NormalizedOcrRow): Boolean {
    val firstCol = row.columns.firstOrNull()?.lowercase(Locale.US)?.trim().orEmpty()
    return firstCol == "balance" || firstCol == "balance:" || firstCol == "total balance" || firstCol == "total balance:"
  }

  private fun looksLikeFinancialLabel(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    return containsAny(
      lower,
      "balance", "principal", "loan amount", "installment", "repayment",
      "interest", "service fee", "processing", "other fee", "amount received",
      "net proceeds", "next due", "next payment", "final payment", "maturity",
      "lender", "tenor", "term", "status"
    )
  }

  private fun looksLikeScheduleRow(
    row: NormalizedOcrRow,
    numericOrder: NumericDateOrder,
    contextYear: Int?,
    referenceDate: LocalDate
  ): Boolean {
    val lower = row.text.lowercase(Locale.US)
    val hasDate = FlexibleDateParser.parseSingleDate(row.text, numericOrder, contextYear, referenceDate) != null
    val hasOrdinal = hasInstallmentOrdinal(row.text)
    val hasMoney = extractBestMoneyFromCleanText(row.text, allowBare = false) != null
    return (hasDate && (hasMoney || hasExplicitScheduleStatus(lower) || hasOrdinal)) ||
      (hasOrdinal && hasMoney && !containsAny(lower, "balance", "principal"))
  }

  private val scheduleStatusWordRegex = Regex(
    """\b(?:paid|settled|completed|upcoming|unpaid|overdue|past\s+due|scheduled|pending)\b|(?<!next\s)(?<!amount\s)(?<!total\s)\bdue\b(?!\s*(?:date|on)\b)""",
    RegexOption.IGNORE_CASE
  )

  private fun hasExplicitScheduleStatus(lower: String): Boolean =
    scheduleStatusWordRegex.containsMatchIn(lower)

  private fun parseInstallmentStatus(lower: String): ExtractedInstallmentStatus = when {
    containsAny(lower, "unpaid", "not paid") -> ExtractedInstallmentStatus.UPCOMING
    containsAny(lower, "paid", "settled", "completed") -> ExtractedInstallmentStatus.PAID
    containsAny(lower, "overdue", "past due", "missed") -> ExtractedInstallmentStatus.OVERDUE
    containsAny(lower, "upcoming", "due", "scheduled", "pending", "next") -> ExtractedInstallmentStatus.UPCOMING
    else -> ExtractedInstallmentStatus.UNKNOWN
  }

  private fun hasInstallmentOrdinal(text: String): Boolean =
    extractInstallmentOrdinal(text) != null

  private fun extractInstallmentOrdinal(text: String): Int? {
    val m1 = Regex("""(?:installment|payment|amortization|due)\s*#?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE).find(text)
    if (m1 != null) return m1.groupValues[1].toIntOrNull()
    val m2 = Regex("""(?:^|\|\s*)#?(\d{1,2})(?:st|nd|rd|th)?\b(?=\s*(?:\||$|·|-|installment|payment))""", RegexOption.IGNORE_CASE).find(text)
    if (m2 != null) return m2.groupValues[1].toIntOrNull()
    return null
  }

  private fun looksOnlyCurrencyOrDate(text: String): Boolean {
    val trimmed = text.trim()
    return prefixedPesoRegex.matches(trimmed) || FlexibleDateParser.parseSingleDate(trimmed) != null
  }

  private fun classifyDocumentKind(hasSummaryScalars: Boolean, scheduleRowCount: Int): ExtractedDocumentKind = when {
    hasSummaryScalars && scheduleRowCount >= 2 -> ExtractedDocumentKind.MIXED_DETAILS_AND_SCHEDULE
    hasSummaryScalars && scheduleRowCount == 0 -> ExtractedDocumentKind.SUMMARY_ONLY
    !hasSummaryScalars && scheduleRowCount >= 1 -> ExtractedDocumentKind.SCHEDULE_ONLY
    hasSummaryScalars -> ExtractedDocumentKind.COMPLETE_DETAILS
    else -> ExtractedDocumentKind.UNSTRUCTURED
  }

  private val installmentKeywordTypoRegex = Regex("""\binstal[l1i]{0,3}ment(s?)\b""")

  private fun canonicalizeKeywords(text: String): String =
    text.lowercase(Locale.US)
      .replace('\u0131', 'i')
      .replace(installmentKeywordTypoRegex, "installment$1")
      .replace("narme", "name")

  private fun containsAny(text: String, vararg keywords: String): Boolean =
    keywords.any { text.contains(it) }
}
