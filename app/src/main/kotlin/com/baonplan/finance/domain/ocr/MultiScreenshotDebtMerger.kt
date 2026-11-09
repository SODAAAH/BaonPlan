package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Combines single-screenshot extractions into one or more loan drafts.
 * Keeps distinct loans from the same lender strictly separated and preserves
 * original schedule dates without inventing missing intervals.
 */
object MultiScreenshotDebtMerger {

  private val stopWords = setOf(
    "loan", "installment", "order", "plan", "repayment", "schedule",
    "payment", "bill", "credit", "summary", "details", "account", "the", "for"
  )

  fun merge(
    pages: List<SingleScreenshotExtraction>,
    existingValidator: (DebtImportDraft) -> List<ImportValidationIssue> = { emptyList() },
    duplicateMatcher: (DebtImportDraft) -> List<DuplicateDebtMatch> = { emptyList() }
  ): ScanAnalysisOutcome {
    val meaningful = pages.filter { hasAnyMeaningfulSignal(it) }
    if (meaningful.isEmpty()) {
      return ScanAnalysisOutcome.Failed(
        userMessage = "Could not detect recognizable loan amounts, dates, or lender details. Try clearer screenshots or enter details manually.",
        failedScreenshotIndices = pages.map { it.screenshotIndex }
      )
    }

    val groups = mutableListOf<MutableList<SingleScreenshotExtraction>>()
    for (page in meaningful) {
      val compatibleGroup = groups.firstOrNull { group ->
        group.all { member -> areSameLoan(member, page) }
      }
      if (compatibleGroup != null) {
        compatibleGroup.add(page)
      } else {
        groups.add(mutableListOf(page))
      }
    }

    val drafts = groups.mapIndexed { groupIdx, groupPages ->
      val baseDraft = mergeCompatibleGroup(groupIdx, groupPages)
      val issues = (baseDraft.validationIssues + existingValidator(baseDraft))
        .distinctBy { Triple(it.fieldKey, it.severity, it.title) }
      val dupes = duplicateMatcher(baseDraft.copy(validationIssues = issues))
      baseDraft.copy(validationIssues = issues, duplicateMatches = dupes)
    }

    return if (drafts.size == 1) {
      ScanAnalysisOutcome.SingleLoan(drafts.single())
    } else {
      val labels = drafts.joinToString(", ") {
        it.loanName.value ?: it.lender.value ?: "Loan ${it.candidateId}"
      }
      ScanAnalysisOutcome.MultipleLoans(
        drafts = drafts,
        explanation = "Detected ${drafts.size} separate loans ($labels). Unrelated loans are kept separate so balances and due dates never mix."
      )
    }
  }

  private fun hasAnyMeaningfulSignal(p: SingleScreenshotExtraction): Boolean =
    p.lender.value != null ||
      p.originalPrincipalCents.value != null ||
      p.outstandingBalanceCents.value != null ||
      p.remainingInstallmentTotalCents.value != null ||
      p.installmentAmountCents.value != null ||
      p.totalRepaymentCents.value != null ||
      p.nextDueDateIso.value != null ||
      p.installmentRows.isNotEmpty()

  internal fun areSameLoan(a: SingleScreenshotExtraction, b: SingleScreenshotExtraction): Boolean {
    // 1. Conflicting non-generic lenders -> strictly separate
    val lenderA = a.lender.value
    val lenderB = b.lender.value
    if (!lenderA.isNullOrBlank() && !lenderB.isNullOrBlank() && !lendersCompatible(lenderA, lenderB)) {
      return false
    }

    // 2. Conflicting reference / order IDs -> strictly separate (even within same lender!)
    val refA = a.loanReferenceId.value?.trim()
    val refB = b.loanReferenceId.value?.trim()
    if (!refA.isNullOrBlank() && !refB.isNullOrBlank()) {
      return refA.equals(refB, ignoreCase = true)
    }

    // 3. Conflicting explicit loan names -> strictly separate
    if (a.loanName.origin == FieldOrigin.EXTRACTED &&
      b.loanName.origin == FieldOrigin.EXTRACTED &&
      !a.loanName.value.isNullOrBlank() &&
      !b.loanName.value.isNullOrBlank()
    ) {
      val nameSim = tokenSimilarity(a.loanName.value, b.loanName.value)
      if (nameSim < 0.4f) return false
    }

    // 4. Hard financial figure contradictions on core terms -> strictly separate
    val origA = a.originalPrincipalCents.value
    val origB = b.originalPrincipalCents.value
    if (origA != null && origB != null && abs(origA - origB) > 100L) return false

    val totalA = a.totalRepaymentCents.value
    val totalB = b.totalRepaymentCents.value
    if (totalA != null && totalB != null && abs(totalA - totalB) > 100L) return false

    val instA = a.installmentAmountCents.value
    val instB = b.installmentAmountCents.value
    if (instA != null && instB != null && abs(instA - instB) > 100L) return false

    val countA = a.originalInstallmentCount.value
    val countB = b.originalInstallmentCount.value
    if (countA != null && countB != null && countA != countB) return false

    // 5. Two full summary pages with differing balances or due dates are likely two distinct loans from the same lender
    val balA = a.outstandingBalanceCents.value
    val balB = b.outstandingBalanceCents.value
    val bothHaveBalances = balA != null && balB != null
    if (balA != null && balB != null && abs(balA - balB) > 100L) {
      return false
    }
    val nextA = a.nextDueDateIso.value
    val nextB = b.nextDueDateIso.value
    val bothSummary = a.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY &&
      b.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY
    if (bothSummary && nextA != null && nextB != null && nextA != nextB) {
      return false
    }

    // 6. Overlapping schedule rows on identical due dates/installment numbers must not contradict
    if (a.installmentRows.isNotEmpty() && b.installmentRows.isNotEmpty()) {
      for (rowA in a.installmentRows) {
        val conflictSameDate = rowA.dueDateIso != null && b.installmentRows.any { rowB ->
          rowB.dueDateIso == rowA.dueDateIso &&
            rowA.amountCents != null &&
            rowB.amountCents != null &&
            abs(rowA.amountCents - rowB.amountCents) > 100L
        }
        if (conflictSameDate) return false

        val conflictSameNum = rowA.installmentNumberHint != null && b.installmentRows.any { rowB ->
          rowB.installmentNumberHint == rowA.installmentNumberHint &&
            rowA.dueDateIso != null && rowB.dueDateIso != null && rowA.dueDateIso != rowB.dueDateIso
        }
        if (conflictSameNum) return false
      }
    }

    // 7. Require positive evidence linking the pages (shared ref, matching amounts, matching schedule/summary link, or complementary details+schedule where one lacks identifying scalar)
    val sameLender = !lenderA.isNullOrBlank() && !lenderB.isNullOrBlank() && lendersCompatible(lenderA, lenderB)
    val complementaryKinds =
      (a.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY && b.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY) ||
        (b.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY && a.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY)

    val matchingBalance = balA != null && balB != null && abs(balA - balB) <= 100L
    val matchingOrig = origA != null && origB != null && abs(origA - origB) <= 100L
    val matchingInst = instA != null && instB != null && abs(instA - instB) <= 100L
    val matchingNextDate = nextA != null && nextB != null && nextA == nextB
    val scheduleDateMatchesSummaryNext = (nextA != null && b.installmentRows.any { it.dueDateIso == nextA }) ||
      (nextB != null && a.installmentRows.any { it.dueDateIso == nextB })

    if (matchingOrig || matchingBalance || matchingInst || matchingNextDate || scheduleDateMatchesSummaryNext) {
      return true
    }

    // Continuation of same schedule or summary + schedule (e.g., schedule-only screenshot without repeated header balance/lender)
    if (complementaryKinds && (sameLender || lenderA == null || lenderB == null)) {
      // Also check that if one has balance & other is schedule-only with rows, unpaid schedule sum or row amounts aren't absurdly mismatched
      val summary = if (a.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY) b else a
      val schedule = if (a.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY) a else b
      val summaryBal = summary.remainingInstallmentTotalCents.value ?: summary.outstandingBalanceCents.value
      val schedUnpaid = schedule.installmentRows
        .filter { it.status != ExtractedInstallmentStatus.PAID }
        .mapNotNull { it.amountCents }
      if (summaryBal != null && schedUnpaid.isNotEmpty()) {
        val rowAmt = schedUnpaid.first()
        // If even a single installment row exceeds the summary's entire remaining balance significantly, they are different loans!
        if (rowAmt > summaryBal + 100L) return false
      }
      return true
    }

    // Two pure schedule-only pages from the same plan (no contradictions, overlapping/adjacent installment numbers or shared dates)
    if (a.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY && b.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY) {
      return sameLender || lenderA == null || lenderB == null
    }

    return false
  }

  private fun mergeCompatibleGroup(
    groupIdx: Int,
    pages: List<SingleScreenshotExtraction>
  ): DebtImportDraft {
    val mergeNotes = mutableListOf<String>()
    val extraIssues = mutableListOf<ImportValidationIssue>()
    extraIssues.addAll(pages.flatMap { it.issues })

    fun <T> mergeScalar(
      fieldName: String,
      fieldKey: String,
      selector: (SingleScreenshotExtraction) -> ExtractedField<T>,
      isDifferent: (T, T) -> Boolean = { x, y -> x != y },
      formatter: (T) -> String = { it.toString() }
    ): ExtractedField<T> {
      val candidates = pages.map { selector(it) }.filter { it.value != null }
      if (candidates.isEmpty()) return ExtractedField.notDetected()

      val extracted = candidates.filter { it.origin == FieldOrigin.EXTRACTED }
      val pool = extracted.ifEmpty { candidates }
      val primary = pool.sortedWith(
        compareBy<ExtractedField<T>> { confidenceRank(it.confidence) }.thenBy { it.sourceScreenshotIndex ?: 0 }
      ).first()

      val conflicting = pool.firstOrNull { cand ->
        cand.value != null && primary.value != null && isDifferent(primary.value, cand.value)
      }
      return if (conflicting != null && primary.value != null && conflicting.value != null) {
        val pIdx = (primary.sourceScreenshotIndex ?: 0) + 1
        val cIdx = (conflicting.sourceScreenshotIndex ?: 0) + 1
        val reason = "Mismatch across screenshots: #$pIdx shows ${formatter(primary.value)}, #$cIdx shows ${formatter(conflicting.value)}"
        extraIssues.add(
          ImportValidationIssue(
            fieldKey = fieldKey,
            severity = ImportSeverity.WARNING,
            title = "$fieldName differs across screenshots",
            message = reason
          )
        )
        primary.copy(confidence = ExtractionConfidence.CONFLICT, verificationReason = reason)
      } else {
        primary
      }
    }

    val moneyDiff: (Long, Long) -> Boolean = { x, y -> abs(x - y) > 100L }
    val moneyFmt: (Long) -> String = { MoneyFormatter.formatPeso(it) }

    val lender = mergeLenderField(pages)
    val loanType = mergeScalar("Loan type", "category", { it.loanType })
    val refId = mergeScalar("Reference ID", "loanReferenceId", { it.loanReferenceId })
    val loanName = mergeLoanNameField(pages, lender.value, refId.value, loanType.value)

    val originalPrincipal = mergeScalar("Original loan amount", "originalPrincipalCents", { it.originalPrincipalCents }, moneyDiff, moneyFmt)
    val amountReceived = mergeScalar("Amount received", "amountReceivedCents", { it.amountReceivedCents }, moneyDiff, moneyFmt)
    val outstandingBalance = mergeScalar("Current balance", "outstandingBalanceCents", { it.outstandingBalanceCents }, moneyDiff, moneyFmt)
    val remInstTotal = mergeScalar("Remaining installment total", "remainingInstallmentTotalCents", { it.remainingInstallmentTotalCents }, moneyDiff, moneyFmt)
    val installmentAmount = mergeScalar("Installment amount", "installmentAmountCents", { it.installmentAmountCents }, moneyDiff, moneyFmt)
    val origCount = mergeScalar("Original installment count", "originalInstallmentCount", { it.originalInstallmentCount })
    val paidCount = mergeScalar("Paid installments", "paidInstallmentsCount", { it.paidInstallmentsCount })
    val remCountExplicit = mergeScalar("Remaining installments", "remainingInstallmentsCount", { it.remainingInstallmentsCount })

    val totalRepayment = mergeScalar("Total repayment", "totalRepaymentCents", { it.totalRepaymentCents }, moneyDiff, moneyFmt)
    val interest = mergeScalar("Interest", "interestCents", { it.interestCents }, moneyDiff, moneyFmt)
    val serviceFee = mergeScalar("Service fee", "serviceFeeCents", { it.serviceFeeCents }, moneyDiff, moneyFmt)
    val processingFee = mergeScalar("Processing fee", "processingFeeCents", { it.processingFeeCents }, moneyDiff, moneyFmt)
    val otherFees = mergeScalar("Other fees", "otherFeesCents", { it.otherFeesCents }, moneyDiff, moneyFmt)
    val loanStatus = mergeScalar("Loan status", "loanStatus", { it.loanStatus })

    // Deduplicate and merge installment schedule rows in chronological order
    val mergedRows = mergeScheduleRows(pages.flatMap { it.installmentRows })

    val unpaidRows = mergedRows.filter {
      it.status == ExtractedInstallmentStatus.UPCOMING ||
        it.status == ExtractedInstallmentStatus.OVERDUE ||
        it.status == ExtractedInstallmentStatus.UNKNOWN
    }
    val paidRows = mergedRows.filter { it.status == ExtractedInstallmentStatus.PAID }

    // Distinct APP_CALCULATED schedule sums
    val unpaidWithAmounts = unpaidRows.mapNotNull { it.amountCents }
    val calcUnpaidSum = if (unpaidWithAmounts.isNotEmpty() && unpaidWithAmounts.size == unpaidRows.size) {
      ExtractedField.calculated(
        value = unpaidWithAmounts.sum(),
        sourceSnippet = "${unpaidRows.size} unpaid schedule rows",
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Calculated by summing ${unpaidRows.size} extracted unpaid installments"
      )
    } else if (unpaidWithAmounts.isNotEmpty()) {
      ExtractedField.calculated(
        value = unpaidWithAmounts.sum(),
        sourceSnippet = "${unpaidWithAmounts.size} of ${unpaidRows.size} unpaid rows had amounts",
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Partial sum — some unpaid rows have no detected peso amount"
      )
    } else {
      ExtractedField.notDetected()
    }

    val paidWithAmounts = paidRows.mapNotNull { it.amountCents }
    val calcPaidSum = if (paidWithAmounts.isNotEmpty()) {
      ExtractedField.calculated(
        value = paidWithAmounts.sum(),
        sourceSnippet = "${paidWithAmounts.size} paid schedule rows",
        confidence = if (paidWithAmounts.size == paidRows.size) ExtractionConfidence.HIGH else ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Calculated by summing ${paidWithAmounts.size} extracted paid installments"
      )
    } else {
      ExtractedField.notDetected()
    }

    // Fill counts cleanly as APP_CALCULATED ONLY when not explicitly extracted
    val finalPaidCount = if (paidCount.value != null) {
      paidCount
    } else if (paidRows.isNotEmpty()) {
      ExtractedField.calculated(
        value = paidRows.size,
        sourceSnippet = "${paidRows.size} paid rows in schedule",
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Counted from extracted schedule rows marked Paid"
      )
    } else {
      ExtractedField.notDetected()
    }

    val finalRemCount = when {
      remCountExplicit.value != null -> remCountExplicit
      unpaidRows.isNotEmpty() -> ExtractedField.calculated(
        value = unpaidRows.size,
        sourceSnippet = "${unpaidRows.size} unpaid rows in schedule",
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Counted from extracted upcoming/overdue schedule rows"
      )
      origCount.value != null && finalPaidCount.value != null -> {
        val diff = (origCount.value - finalPaidCount.value).coerceAtLeast(0)
        ExtractedField.calculated(
          value = diff,
          sourceSnippet = "${origCount.value} - ${finalPaidCount.value}",
          confidence = ExtractionConfidence.NEEDS_VERIFICATION,
          verificationReason = "Calculated as original (${origCount.value}) minus paid (${finalPaidCount.value})"
        )
      }
      else -> ExtractedField.notDetected()
    }

    // Next / Final / Last Paid Dates (prefer explicit summary match; fall back to merged schedule rows)
    var nextDueDate = mergeScalar("Next due date", "nextDueDate", { it.nextDueDateIso })
    if (nextDueDate.value == null) {
      val firstUnpaidDated = unpaidRows.firstOrNull { it.dueDateIso != null }
      if (firstUnpaidDated != null) {
        nextDueDate = ExtractedField.extracted(
          value = firstUnpaidDated.dueDateIso!!,
          sourceSnippet = firstUnpaidDated.sourceSnippet,
          screenshotIndex = firstUnpaidDated.screenshotIndex,
          confidence = firstUnpaidDated.dateConfidence,
          verificationReason = firstUnpaidDated.verificationNote
        )
      }
    }

    var finalDueDate = mergeScalar("Final due date", "finalDueDate", { it.finalDueDateIso })
    if (finalDueDate.value == null && mergedRows.size >= 2) {
      val lastDated = mergedRows.lastOrNull { it.dueDateIso != null }
      if (lastDated != null && lastDated.dueDateIso != nextDueDate.value) {
        finalDueDate = ExtractedField.extracted(
          value = lastDated.dueDateIso!!,
          sourceSnippet = lastDated.sourceSnippet,
          screenshotIndex = lastDated.screenshotIndex,
          confidence = lastDated.dateConfidence,
          verificationReason = lastDated.verificationNote
        )
      }
    }

    var lastPaidDate = mergeScalar("Last paid date", "lastPaidDate", { it.lastPaidDateIso })
    if (lastPaidDate.value == null && paidRows.isNotEmpty()) {
      val latestPaid = paidRows.lastOrNull { it.dueDateIso != null }
      if (latestPaid != null) {
        lastPaidDate = ExtractedField.extracted(
          value = latestPaid.dueDateIso!!,
          sourceSnippet = latestPaid.sourceSnippet,
          screenshotIndex = latestPaid.screenshotIndex,
          confidence = latestPaid.dateConfidence
        )
      }
    }

    // Frequency across merged rows (prefers explicit page label, otherwise evaluates merged dates conservatively)
    val explicitFreq = pages.map { it.frequency }.firstOrNull {
      it.value != null && it.value != RepaymentFrequency.UNKNOWN &&
        it.verificationReason?.startsWith("Inferred") != true
    }
    val customDays = mergeScalar("Custom interval days", "customIntervalDays", { it.customIntervalDays })
    val (finalFrequency, finalCustomDays) = if (explicitFreq != null) {
      explicitFreq to customDays
    } else {
      evaluateCombinedFrequency(mergedRows, origCount.value)
    }

    val initialDueStatus = when {
      nextDueDate.value == null -> DueDateStatus.UNKNOWN
      nextDueDate.confidence == ExtractionConfidence.HIGH -> DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
      else -> DueDateStatus.UNCONFIRMED
    }

    if (pages.size > 1) {
      mergeNotes.add("Combined ${pages.size} screenshots (#${pages.joinToString(", #") { "${it.screenshotIndex + 1}" }})")
    }

    // Installment amount fallback across rows if single-page missed it
    val finalInstAmount = if (installmentAmount.value != null) {
      installmentAmount
    } else {
      val distinctAmts = mergedRows.mapNotNull { it.amountCents }.filter { it > 0L }.distinct()
      if (distinctAmts.size == 1 && mergedRows.size >= 2) {
        ExtractedField.extracted(
          value = distinctAmts.single(),
          sourceSnippet = mergedRows.first { it.amountCents == distinctAmts.single() }.sourceSnippet,
          screenshotIndex = mergedRows.first { it.amountCents == distinctAmts.single() }.screenshotIndex,
          confidence = ExtractionConfidence.HIGH
        )
      } else {
        ExtractedField.notDetected()
      }
    }

    return DebtImportDraft(
      candidateId = "loan_group_${groupIdx + 1}",
      sourceScreenshotIndices = pages.map { it.screenshotIndex }.distinct().sorted(),
      lender = lender,
      loanName = loanName,
      loanType = loanType,
      loanReferenceId = refId,
      originalPrincipalCents = originalPrincipal,
      amountReceivedCents = amountReceived,
      outstandingBalanceCents = outstandingBalance,
      remainingInstallmentTotalCents = remInstTotal,
      calculatedUnpaidScheduleSumCents = calcUnpaidSum,
      calculatedPaidScheduleSumCents = calcPaidSum,
      installmentAmountCents = finalInstAmount,
      originalInstallmentCount = origCount,
      paidInstallmentsCount = finalPaidCount,
      remainingInstallmentsCount = finalRemCount,
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
      dueDateStatus = initialDueStatus,
      installmentRows = mergedRows,
      mergeNotes = mergeNotes,
      validationIssues = extraIssues.distinctBy { Triple(it.fieldKey, it.severity, it.title) }
    )
  }

  private fun mergeLenderField(pages: List<SingleScreenshotExtraction>): ExtractedField<String> {
    val withLender = pages.map { it.lender }.filter { !it.value.isNullOrBlank() }
    if (withLender.isEmpty()) return ExtractedField.notDetected()
    // Prefer more specific variant ("Shopee (SPayLater)" over "Shopee")
    return withLender.maxByOrNull { it.value!!.length } ?: withLender.first()
  }

  private fun mergeLoanNameField(
    pages: List<SingleScreenshotExtraction>,
    lender: String?,
    refId: String?,
    loanType: String?
  ): ExtractedField<String> {
    val extracted = pages.map { it.loanName }
      .firstOrNull { it.origin == FieldOrigin.EXTRACTED && !it.value.isNullOrBlank() }
    if (extracted != null) return extracted

    val suggested = pages.map { it.loanName }.firstOrNull { !it.value.isNullOrBlank() }
    if (suggested != null) {
      val enriched = buildString {
        append(lender ?: suggested.value)
        if (!refId.isNullOrBlank() && !suggested.value!!.contains(refId, ignoreCase = true)) {
          append(" #").append(refId)
        } else if (lender != null && !loanType.isNullOrBlank() && !lender.contains(loanType, ignoreCase = true)) {
          append(" ").append(loanType)
        }
      }
      return suggested.copy(value = enriched.take(50))
    }
    return ExtractedField.notDetected()
  }

  internal fun mergeScheduleRows(allRows: List<ExtractedInstallmentRow>): List<ExtractedInstallmentRow> {
    if (allRows.isEmpty()) return emptyList()
    val merged = mutableListOf<ExtractedInstallmentRow>()
    for (row in allRows) {
      val matchIdx = merged.indexOfFirst { existing -> rowsRepresentSameInstallment(existing, row) }
      if (matchIdx == -1) {
        merged.add(row)
      } else {
        merged[matchIdx] = chooseBetterRow(merged[matchIdx], row)
      }
    }
    return merged.sortedWith(
      compareBy<ExtractedInstallmentRow> { it.dueDateIso ?: "9999-12-31" }
        .thenBy { it.installmentNumberHint ?: Int.MAX_VALUE }
        .thenBy { it.screenshotIndex }
    )
  }

  private fun rowsRepresentSameInstallment(a: ExtractedInstallmentRow, b: ExtractedInstallmentRow): Boolean {
    val dateMatches = a.dueDateIso != null && b.dueDateIso != null && a.dueDateIso == b.dueDateIso
    val rawDateMatches = a.rawDateText.isNotBlank() && b.rawDateText.isNotBlank() &&
      a.rawDateText.equals(b.rawDateText, ignoreCase = true)
    val sameHint = a.installmentNumberHint != null && b.installmentNumberHint != null &&
      a.installmentNumberHint == b.installmentNumberHint
    val sameAmountOrMissing = a.amountCents == null || b.amountCents == null ||
      abs(a.amountCents - b.amountCents) <= 100L

    return when {
      dateMatches && sameAmountOrMissing -> true
      dateMatches && sameHint -> true
      rawDateMatches && sameAmountOrMissing && (a.dueDateIso == null || b.dueDateIso == null) -> true
      sameHint && a.dueDateIso == null && b.dueDateIso == null && sameAmountOrMissing -> true
      else -> false
    }
  }

  private fun chooseBetterRow(a: ExtractedInstallmentRow, b: ExtractedInstallmentRow): ExtractedInstallmentRow {
    val betterStatus = when {
      a.status == ExtractedInstallmentStatus.PAID -> a.status
      b.status == ExtractedInstallmentStatus.PAID -> b.status
      a.status != ExtractedInstallmentStatus.UNKNOWN -> a.status
      else -> b.status
    }
    val preferA = (a.amountCents != null && b.amountCents == null) ||
      (confidenceRank(a.dateConfidence) <= confidenceRank(b.dateConfidence) && a.amountCents != null)
    val base = if (preferA) a else b
    val other = if (preferA) b else a
    return base.copy(
      installmentNumberHint = base.installmentNumberHint ?: other.installmentNumberHint,
      dueDateIso = base.dueDateIso ?: other.dueDateIso,
      amountCents = base.amountCents ?: other.amountCents,
      status = betterStatus,
      verificationNote = base.verificationNote ?: other.verificationNote
    )
  }

  private fun evaluateCombinedFrequency(
    rows: List<ExtractedInstallmentRow>,
    origCount: Int?
  ): Pair<ExtractedField<RepaymentFrequency>, ExtractedField<Int>> {
    val dated = rows.mapNotNull { r -> ManilaTime.parseDate(r.dueDateIso)?.let { r to it } }
      .distinctBy { it.second }
      .sortedBy { it.second }
    if (dated.size == 1 && origCount == 1) {
      return ExtractedField.extracted(
        RepaymentFrequency.ONE_TIME,
        dated.first().first.sourceSnippet,
        dated.first().first.screenshotIndex,
        ExtractionConfidence.NEEDS_VERIFICATION,
        "Single installment detected — verify frequency"
      ) to ExtractedField.notDetected()
    }
    if (dated.size < 3) {
      return ExtractedField.extracted(
        value = RepaymentFrequency.UNKNOWN,
        confidence = ExtractionConfidence.NEEDS_VERIFICATION,
        verificationReason = "Fewer than 3 installment dates found — select frequency if known"
      ) to ExtractedField.notDetected()
    }
    val dates = dated.map { it.second }
    val gaps = dates.zipWithNext { x, y -> ChronoUnit.DAYS.between(x, y).toInt() }
    val snippet = dated.take(3).joinToString(" -> ") { ManilaTime.formatShort(ManilaTime.formatIso(it.second)) }
    val firstIdx = dated.first().first.screenshotIndex
    return when {
      gaps.all { it == 7 } ->
        ExtractedField.extracted(RepaymentFrequency.WEEKLY, snippet, firstIdx, ExtractionConfidence.NEEDS_VERIFICATION, "Inferred weekly pattern — confirm") to ExtractedField.notDetected()
      gaps.all { it == 14 } ->
        ExtractedField.extracted(RepaymentFrequency.EVERY_14_DAYS, snippet, firstIdx, ExtractionConfidence.NEEDS_VERIFICATION, "Inferred biweekly pattern — confirm") to ExtractedField.notDetected()
      isMonthlyDates(dates) ->
        ExtractedField.extracted(RepaymentFrequency.MONTHLY, snippet, firstIdx, ExtractionConfidence.NEEDS_VERIFICATION, "Inferred monthly pattern — confirm") to ExtractedField.notDetected()
      gaps.distinct().size == 1 && gaps.first() in 1..180 -> {
        val n = gaps.first()
        ExtractedField.extracted(RepaymentFrequency.CUSTOM_DAYS, snippet, firstIdx, ExtractionConfidence.NEEDS_VERIFICATION, "Inferred every $n days — confirm") to
          ExtractedField.extracted(n, snippet, firstIdx, ExtractionConfidence.NEEDS_VERIFICATION)
      }
      else ->
        ExtractedField.extracted(RepaymentFrequency.IRREGULAR, snippet, firstIdx, ExtractionConfidence.HIGH, "Irregular dates preserved (${gaps.joinToString("d, ")}d)") to ExtractedField.notDetected()
    }
  }

  private fun isMonthlyDates(dates: List<LocalDate>): Boolean =
    dates.zipWithNext().all { (a, b) ->
      a.plusMonths(1) == b ||
        (b.year * 12 + b.monthValue - (a.year * 12 + a.monthValue) == 1 &&
          (a.dayOfMonth == b.dayOfMonth || (a.dayOfMonth == a.lengthOfMonth() && b.dayOfMonth == b.lengthOfMonth())))
    }

  private fun lendersCompatible(a: String, b: String): Boolean {
    val normA = normalizeText(a)
    val normB = normalizeText(b)
    if (normA == normB) return true
    // Allow generic parent ("shopee") to merge with child ("shopee spaylater") only if child isn't contradicting sibling ("sloan")
    return (normA == "shopee" && normB.startsWith("shopee ")) ||
      (normB == "shopee" && normA.startsWith("shopee ")) ||
      (normA == "gcash" && normB.startsWith("gcash ")) ||
      (normB == "gcash" && normA.startsWith("gcash ")) ||
      (normA == "maya" && normB.startsWith("maya ")) ||
      (normB == "maya" && normA.startsWith("maya "))
  }

  private fun tokenSimilarity(a: String, b: String): Float {
    val ta = normalizeText(a).split(' ').filter { it.length > 1 && it !in stopWords }.toSet()
    val tb = normalizeText(b).split(' ').filter { it.length > 1 && it !in stopWords }.toSet()
    if (ta.isEmpty() || tb.isEmpty()) return 0f
    val inter = ta.intersect(tb).size
    val union = ta.union(tb).size
    return inter.toFloat() / union.toFloat()
  }

  private fun normalizeText(s: String): String =
    s.lowercase(Locale.US).replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

  private fun confidenceRank(c: ExtractionConfidence): Int = when (c) {
    ExtractionConfidence.HIGH -> 0
    ExtractionConfidence.NEEDS_VERIFICATION -> 1
    ExtractionConfidence.CONFLICT -> 2
  }
}
