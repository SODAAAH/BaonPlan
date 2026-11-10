package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.ObligationRole
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Combines single-screenshot extractions into one or more coherent loan drafts.
 * Links partial/complementary pages (e.g. Origination + Paid History + Balance/Schedule),
 * prefers newer snapshots when dates/progress prove recency, preserves unresolved
 * conflicts with alternatives, keeps distinct loans from the same lender separate,
 * and supports explicit user-confirmed screenshot grouping.
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

    return buildOutcomeFromGroups(groups, existingValidator, duplicateMatcher)
  }

  /**
   * Applies explicit user-confirmed screenshot groupings (e.g. user combined or separated
   * screenshots manually in ScanDebtSheet) without re-running OCR.
   */
  fun mergeWithCustomGroups(
    pages: List<SingleScreenshotExtraction>,
    groupedScreenshotIndices: List<List<Int>>,
    existingValidator: (DebtImportDraft) -> List<ImportValidationIssue> = { emptyList() },
    duplicateMatcher: (DebtImportDraft) -> List<DuplicateDebtMatch> = { emptyList() }
  ): ScanAnalysisOutcome {
    val pageByIdx = pages.associateBy { it.screenshotIndex }
    val explicitGroups = groupedScreenshotIndices.mapNotNull { indices ->
      val members = indices.distinct().mapNotNull { pageByIdx[it] }.filter { hasAnyMeaningfulSignal(it) }
      members.takeIf { it.isNotEmpty() }
    }
    if (explicitGroups.isEmpty()) {
      return ScanAnalysisOutcome.Failed(
        userMessage = "Could not detect recognizable loan details from the selected screenshots.",
        failedScreenshotIndices = pages.map { it.screenshotIndex }
      )
    }
    return buildOutcomeFromGroups(explicitGroups, existingValidator, duplicateMatcher)
  }

  private fun buildOutcomeFromGroups(
    groups: List<List<SingleScreenshotExtraction>>,
    existingValidator: (DebtImportDraft) -> List<ImportValidationIssue>,
    duplicateMatcher: (DebtImportDraft) -> List<DuplicateDebtMatch>
  ): ScanAnalysisOutcome {
    val drafts = groups.mapIndexed { groupIdx, groupPages ->
      val baseDraft = mergeCompatibleGroup(groupIdx, groupPages)
      val hadExplicitFreq = groupPages.any {
        it.frequency.origin == FieldOrigin.EXTRACTED &&
          it.frequency.value != null &&
          it.frequency.value != RepaymentFrequency.UNKNOWN &&
          it.frequency.value != RepaymentFrequency.IRREGULAR
      }
      val reasonedDraft = FinancialReasoningEngine.enrichMergedDraft(baseDraft, hadExplicitFreq)
      val issues = (reasonedDraft.validationIssues + existingValidator(reasonedDraft))
        .distinctBy { Triple(it.fieldKey, it.severity, it.title) }
      val dupes = duplicateMatcher(reasonedDraft.copy(validationIssues = issues))
      reasonedDraft.copy(validationIssues = issues, duplicateMatches = dupes)
    }

    return if (drafts.size == 1) {
      ScanAnalysisOutcome.SingleLoan(drafts.single())
    } else {
      val labels = drafts.joinToString(", ") {
        it.loanName.value ?: it.lender.value ?: "Loan ${it.candidateId}"
      }
      ScanAnalysisOutcome.MultipleLoans(
        drafts = drafts,
        explanation = "Detected ${drafts.size} separate loans ($labels). Confirm grouping below if screenshots belong to the same loan."
      )
    }
  }

  internal fun hasAnyMeaningfulSignal(p: SingleScreenshotExtraction): Boolean =
    p.lender.value != null ||
      p.originalPrincipalCents.value != null ||
      p.outstandingBalanceCents.value != null ||
      p.amountCurrentlyDueCents.value != null ||
      p.remainingInstallmentTotalCents.value != null ||
      p.installmentAmountCents.value != null ||
      p.totalRepaymentCents.value != null ||
      p.statementBalanceCents.value != null ||
      p.creditLimitCents.value != null ||
      p.minimumDueCents.value != null ||
      p.pastDueAmountCents.value != null ||
      p.nextDueDateIso.value != null ||
      p.installmentRows.isNotEmpty()

  internal fun areSameLoan(a: SingleScreenshotExtraction, b: SingleScreenshotExtraction): Boolean {
    // 1. Conflicting non-generic lenders/profiles -> strictly separate
    val lenderA = a.lender.value
    val lenderB = b.lender.value
    if (!lenderA.isNullOrBlank() && !lenderB.isNullOrBlank() && !lendersCompatible(lenderA, lenderB)) {
      return false
    }

    // 1b. Reference / Order IDs are authoritative identity when present on both pages
    val refA = a.loanReferenceId.value?.trim()
    val refB = b.loanReferenceId.value?.trim()
    if (!refA.isNullOrBlank() && !refB.isNullOrBlank()) {
      return refA.equals(refB, ignoreCase = true)
    }

    // 2. Incompatible known product classifications (e.g. SPayLater vs SLoan, Credit Line vs Cash Loan)
    val prodA = a.productClassification.value ?: LoanProductClassification.UNKNOWN
    val prodB = b.productClassification.value ?: LoanProductClassification.UNKNOWN
    val isRestructureTransition = a.scheduleVersion != b.scheduleVersion &&
      (prodA == LoanProductClassification.FLEXIBLE_OR_RESTRUCTURED_LOAN ||
        prodB == LoanProductClassification.FLEXIBLE_OR_RESTRUCTURED_LOAN)
    if (!isRestructureTransition &&
      prodA != LoanProductClassification.UNKNOWN &&
      prodB != LoanProductClassification.UNKNOWN &&
      prodA != prodB
    ) {
      return false
    }

    // 3. Conflicting explicit loan names -> strictly separate
    val hasMatchingExplicitName = if (a.loanName.origin == FieldOrigin.EXTRACTED &&
      b.loanName.origin == FieldOrigin.EXTRACTED &&
      !a.loanName.value.isNullOrBlank() &&
      !b.loanName.value.isNullOrBlank()
    ) {
      val nameSim = tokenSimilarity(a.loanName.value, b.loanName.value)
      if (nameSim < 0.4f) return false
      nameSim >= 0.65f
    } else false

    val hasScheduleRestructure = (a.scheduleVersion != b.scheduleVersion ||
      (a.scheduleEffectiveDateIso != null && b.scheduleEffectiveDateIso != null &&
        a.scheduleEffectiveDateIso != b.scheduleEffectiveDateIso)) &&
      (hasMatchingExplicitName ||
        (a.originalPrincipalCents.extractedOnlyOrNull() != null &&
          b.originalPrincipalCents.extractedOnlyOrNull() != null &&
          abs(a.originalPrincipalCents.extractedOnlyOrNull()!! - b.originalPrincipalCents.extractedOnlyOrNull()!!) <= 100L))

    // 4. Hard immutable core-term contradictions -> strictly separate (unless explicitly restructured schedule version)
    val origA = a.originalPrincipalCents.extractedOnlyOrNull()
    val origB = b.originalPrincipalCents.extractedOnlyOrNull()
    if (origA != null && origB != null && abs(origA - origB) > 100L) return false

    if (!hasScheduleRestructure) {
      val totalA = a.totalRepaymentCents.extractedOnlyOrNull()
      val totalB = b.totalRepaymentCents.extractedOnlyOrNull()
      if (totalA != null && totalB != null && abs(totalA - totalB) > 100L) return false

      val instA = a.installmentAmountCents.value
      val instB = b.installmentAmountCents.value
      if (instA != null && instB != null && abs(instA - instB) > 100L) return false

      val countA = a.originalInstallmentCount.extractedOnlyOrNull()
      val countB = b.originalInstallmentCount.extractedOnlyOrNull()
      if (countA != null && countB != null && countA != countB) return false
    }

    val instA = a.installmentAmountCents.value
    val instB = b.installmentAmountCents.value

    // 5. Differing balances or differing EXPLICIT due dates without shared identity/progression -> separate loans!
    val hasSnapshotProgression = compareRecency(a, b) != 0 &&
      (hasMatchingExplicitName || (origA != null && origB != null && abs(origA - origB) <= 100L))

    val balA = a.outstandingBalanceCents.extractedOnlyOrNull()
    val balB = b.outstandingBalanceCents.extractedOnlyOrNull()
    if (balA != null && balB != null && abs(balA - balB) > 100L && !hasMatchingExplicitName && !hasSnapshotProgression) {
      return false
    }

    val nextA = if (a.isNextDueDateExplicit) a.nextDueDateIso.value else null
    val nextB = if (b.isNextDueDateExplicit) b.nextDueDateIso.value else null
    val bothSummary = a.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY &&
      b.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY
    if (bothSummary && nextA != null && nextB != null && nextA != nextB && !hasMatchingExplicitName && !hasSnapshotProgression) {
      return false
    }

    // 6. Overlapping schedule rows on identical due dates/installment numbers must not contradict on amount/date
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

    // 7. Positive linking signals
    val sameLender = !lenderA.isNullOrBlank() && !lenderB.isNullOrBlank() && lendersCompatible(lenderA, lenderB)
    val matchingBalance = balA != null && balB != null && abs(balA - balB) <= 100L
    val matchingOrig = origA != null && origB != null && abs(origA - origB) <= 100L
    val matchingInst = instA != null && instB != null && abs(instA - instB) <= 100L
    val matchingNextDate = nextA != null && nextB != null && nextA == nextB
    val scheduleDateMatchesSummaryNext = (nextA != null && b.installmentRows.any { it.dueDateIso == nextA }) ||
      (nextB != null && a.installmentRows.any { it.dueDateIso == nextB })
    val scheduleRowMatchesOtherInstAmt = (instA != null && b.installmentRows.any { r -> r.amountCents != null && abs(r.amountCents - instA) <= 100L }) ||
      (instB != null && a.installmentRows.any { r -> r.amountCents != null && abs(r.amountCents - instB) <= 100L })
    val overlappingScheduleDates = a.installmentRows.mapNotNull { it.dueDateIso }.toSet()
      .intersect(b.installmentRows.mapNotNull { it.dueDateIso }.toSet()).isNotEmpty()

    if (hasMatchingExplicitName || matchingOrig || matchingBalance || matchingInst ||
      matchingNextDate || scheduleDateMatchesSummaryNext || scheduleRowMatchesOtherInstAmt ||
      overlappingScheduleDates
    ) {
      return true
    }

    // 8. Disjoint complementary facets of a single loan (e.g. Page 1 = Origination terms,
    //    Page 2 = Paid History, Page 3 = Upcoming/Balance) when lenders are compatible and facets don't clash
    val complementaryScheduleSummary =
      (a.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY && b.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY) ||
        (b.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY && a.documentKind != ExtractedDocumentKind.SCHEDULE_ONLY)
    val disjointPartialSummaries =
      ((origA != null && balA == null && origB == null && balB != null) ||
        (origB != null && balB == null && origA == null && balA != null) ||
        (a.lastPaidDateIso.value != null && b.lastPaidDateIso.value == null && balA == null && balB != null) ||
        (b.lastPaidDateIso.value != null && a.lastPaidDateIso.value == null && balB == null && balA != null))

    if ((complementaryScheduleSummary || disjointPartialSummaries) &&
      (sameLender || lenderA == null || lenderB == null)
    ) {
      // Sanity check balance vs installment row amount if present
      val checkBal = balA ?: balB ?: a.remainingInstallmentTotalCents.extractedOnlyOrNull() ?: b.remainingInstallmentTotalCents.extractedOnlyOrNull()
      val checkInst = instA ?: instB
      if (checkBal != null && checkInst != null && checkInst > checkBal + 100L) return false
      return true
    }

    if (a.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY && b.documentKind == ExtractedDocumentKind.SCHEDULE_ONLY) {
      return sameLender || lenderA == null || lenderB == null
    }

    return false
  }

  /**
   * Returns +1 if [a] is demonstrably newer than [b], -1 if [b] is newer, 0 if indeterminate.
   */
  internal fun compareRecency(a: SingleScreenshotExtraction, b: SingleScreenshotExtraction): Int {
    if (a.scheduleVersion != b.scheduleVersion) {
      return a.scheduleVersion.compareTo(b.scheduleVersion).coerceIn(-1, 1)
    }

    val effA = a.scheduleEffectiveDateIso
    val effB = b.scheduleEffectiveDateIso
    if (!effA.isNullOrBlank() && !effB.isNullOrBlank() && effA != effB) {
      return effA.compareTo(effB).coerceIn(-1, 1)
    }

    val stmtA = a.statementDateIso.value
    val stmtB = b.statementDateIso.value
    if (!stmtA.isNullOrBlank() && !stmtB.isNullOrBlank() && stmtA != stmtB) {
      return stmtA.compareTo(stmtB).coerceIn(-1, 1)
    }

    val docA = a.documentDateIso
    val docB = b.documentDateIso
    if (!docA.isNullOrBlank() && !docB.isNullOrBlank() && docA != docB) {
      return docA.compareTo(docB).coerceIn(-1, 1)
    }

    val paidDateA = a.lastPaidDateIso.value
    val paidDateB = b.lastPaidDateIso.value
    if (!paidDateA.isNullOrBlank() && !paidDateB.isNullOrBlank() && paidDateA != paidDateB) {
      return paidDateA.compareTo(paidDateB).coerceIn(-1, 1)
    }

    val paidCountA = a.paidInstallmentsCount.value ?: a.installmentRows.count { it.status.isSettledPaid }.takeIf { it > 0 }
    val paidCountB = b.paidInstallmentsCount.value ?: b.installmentRows.count { it.status.isSettledPaid }.takeIf { it > 0 }
    if (paidCountA != null && paidCountB != null && paidCountA != paidCountB) {
      return paidCountA.compareTo(paidCountB).coerceIn(-1, 1)
    }

    return 0
  }

  private fun mergeCompatibleGroup(
    groupIdx: Int,
    pages: List<SingleScreenshotExtraction>
  ): DebtImportDraft {
    val mergeNotes = mutableListOf<String>()
    val extraIssues = mutableListOf<ImportValidationIssue>()
    extraIssues.addAll(pages.flatMap { it.issues })
    val pageByIdx = pages.associateBy { it.screenshotIndex }

    fun <T : Any> mergeScalar(
      fieldName: String,
      fieldKey: String,
      selector: (SingleScreenshotExtraction) -> ExtractedField<T>,
      isDifferent: (T, T) -> Boolean = { x, y -> x != y },
      formatter: (T) -> String = { it.toString() },
      preferRecencyResolution: Boolean = true
    ): ExtractedField<T> {
      val candidates = pages.map { selector(it) }.filter { it.value != null }
      if (candidates.isEmpty()) return ExtractedField.notDetected()

      val extracted = candidates.filter { it.origin == FieldOrigin.EXTRACTED }
      val pool = extracted.ifEmpty { candidates }
      val sortedPool = pool.sortedWith(
        compareBy<ExtractedField<T>> { it.confidence.rank }.thenBy { it.sourceScreenshotIndex ?: 0 }
      )
      val best = sortedPool.first()

      // Check distinct conflicting values among candidates
      val distinctConflicting = mutableListOf<ExtractedField<T>>()
      for (cand in sortedPool) {
        if (cand.value != null && best.value != null && isDifferent(best.value, cand.value) &&
          distinctConflicting.none { prev -> prev.value != null && !isDifferent(prev.value, cand.value) }
        ) {
          distinctConflicting.add(cand)
        }
      }

      if (distinctConflicting.isEmpty()) {
        // Check if multiple pages agree -> boost confidence & explain corroboration
        val distinctPages = pool.mapNotNull { it.sourceScreenshotIndex }.distinct()
        return if (distinctPages.size >= 2 && best.origin == FieldOrigin.EXTRACTED) {
          val pageLabels = distinctPages.sorted().joinToString(" & ") { "#${it + 1}" }
          val upgradedConf = if (best.confidence == ExtractionConfidence.MEDIUM) ExtractionConfidence.HIGH else best.confidence
          val note = best.verificationReason?.let { "$it (Confirmed across screenshots $pageLabels.)" }
            ?: "Agreed across screenshots $pageLabels."
          best.copy(confidence = upgradedConf, verificationReason = note)
        } else {
          best
        }
      }

      // Check if recency establishes which screenshot is newer
      if (preferRecencyResolution && best.value != null) {
        val allInvolved = listOf(best) + distinctConflicting
        val newestCandidate = allInvolved.maxWithOrNull { x, y ->
          val pageX = x.sourceScreenshotIndex?.let { pageByIdx[it] }
          val pageY = y.sourceScreenshotIndex?.let { pageByIdx[it] }
          if (pageX != null && pageY != null) compareRecency(pageX, pageY) else 0
        }
        val newestPage = newestCandidate?.sourceScreenshotIndex?.let { pageByIdx[it] }
        val isStrictlyNewer = newestCandidate != null && newestPage != null &&
          allInvolved.filter { it !== newestCandidate }.all { other ->
            val otherPage = other.sourceScreenshotIndex?.let { pageByIdx[it] }
            otherPage != null && compareRecency(newestPage, otherPage) > 0
          }

        if (isStrictlyNewer && newestCandidate.value != null) {
          val newVal = newestCandidate.value
          val newIdx = newestPage.screenshotIndex + 1
          val olderOthers = allInvolved.filter { it !== newestCandidate && it.value != null && isDifferent(newVal, it.value) }
          val alternatives = olderOthers.map { alt ->
            val aIdx = (alt.sourceScreenshotIndex ?: 0) + 1
            FieldAlternative(
              value = alt.value!!,
              label = "${formatter(alt.value)} (Older screenshot #$aIdx)",
              origin = alt.origin,
              screenshotIndex = alt.sourceScreenshotIndex,
              sourceSnippet = alt.sourceSnippet
            )
          }
          val recencyDetail = newestPage.documentDateIso?.let { "As of ${ManilaTime.formatDisplay(it)}" }
            ?: newestPage.lastPaidDateIso.value?.let { "Later paid date ${ManilaTime.formatDisplay(it)}" }
            ?: "More completed payments shown"
          val explanation = "Preferred newer screenshot #$newIdx ($recencyDetail). Older value(s) preserved in alternatives."
          mergeNotes.add("$fieldName: $explanation")
          return newestCandidate.copy(
            confidence = ExtractionConfidence.MEDIUM,
            verificationReason = explanation,
            hasConflict = false,
            alternatives = alternatives
          )
        }
      }

      // Unresolved conflict — flag LOW + keep all alternative choices without silent overwrite
      val pIdx = (best.sourceScreenshotIndex ?: 0) + 1
      val firstOther = distinctConflicting.first()
      val cIdx = (firstOther.sourceScreenshotIndex ?: 0) + 1
      val reason = "Mismatch across screenshots: #$pIdx shows ${formatter(best.value!!)}, #$cIdx shows ${formatter(firstOther.value!!)}."
      extraIssues.add(
        ImportValidationIssue(
          fieldKey = fieldKey,
          severity = ImportSeverity.WARNING,
          title = "$fieldName differs across screenshots",
          message = reason
        )
      )
      val alternatives = distinctConflicting.mapNotNull { alt ->
        alt.value?.let { v ->
          val aIdx = (alt.sourceScreenshotIndex ?: 0) + 1
          FieldAlternative(
            value = v,
            label = "${formatter(v)} (Screenshot #$aIdx)",
            origin = alt.origin,
            screenshotIndex = alt.sourceScreenshotIndex,
            sourceSnippet = alt.sourceSnippet
          )
        }
      }
      return best.copy(
        confidence = ExtractionConfidence.LOW,
        verificationReason = reason,
        hasConflict = true,
        alternatives = alternatives
      )
    }

    val moneyDiff: (Long, Long) -> Boolean = { x, y -> abs(x - y) > 100L }
    val moneyFmt: (Long) -> String = { MoneyFormatter.formatPeso(it) }

    val lender = mergeLenderField(pages)
    val providerProfileId = pages.firstOrNull { !it.providerProfileId.isNullOrBlank() }?.providerProfileId
    val productClassification = mergeScalar("Product type", "productClassification", { it.productClassification })
    val applicationProvider = mergeScalar("Application provider", "applicationProvider", { it.applicationProvider })
    val actualCreditor = mergeScalar("Actual creditor", "actualCreditor", { it.actualCreditor })
    val loanType = mergeScalar("Loan type", "category", { it.loanType })
    val refId = mergeScalar("Reference ID", "loanReferenceId", { it.loanReferenceId })
    val loanName = mergeLoanNameField(pages, lender.value, refId.value, loanType.value)
    val loanBookingDate = mergeScalar("Booking date", "loanBookingDate", { it.loanBookingDateIso })

    val originalPrincipal = mergeScalar("Original loan amount", "originalPrincipalCents", { it.originalPrincipalCents }, moneyDiff, moneyFmt)
    val disbursedAmount = mergeScalar("Disbursed amount", "disbursedAmountCents", { it.disbursedAmountCents }, moneyDiff, moneyFmt)
    val amountReceived = mergeScalar("Amount received", "amountReceivedCents", { it.amountReceivedCents }, moneyDiff, moneyFmt)
    val outstandingBalance = mergeScalar("Current balance", "outstandingBalanceCents", { it.outstandingBalanceCents }, moneyDiff, moneyFmt)
    val amountCurrentlyDue = mergeScalar("Amount currently due", "amountCurrentlyDueCents", { it.amountCurrentlyDueCents }, moneyDiff, moneyFmt)
    val pastDueAmount = mergeScalar("Past due amount", "pastDueAmountCents", { it.pastDueAmountCents }, moneyDiff, moneyFmt)
    val unpaidCharges = mergeScalar("Unpaid charges", "unpaidChargesCents", { it.unpaidChargesCents }, moneyDiff, moneyFmt)
    val remInstTotal = mergeScalar("Remaining installment total", "remainingInstallmentTotalCents", { it.remainingInstallmentTotalCents }, moneyDiff, moneyFmt)
    val installmentAmount = mergeScalar("Installment amount", "installmentAmountCents", { it.installmentAmountCents }, moneyDiff, moneyFmt)
    val origCount = mergeScalar("Original installment count", "originalInstallmentCount", { it.originalInstallmentCount })
    val paidCount = mergeScalar("Paid installments", "paidInstallmentsCount", { it.paidInstallmentsCount })
    val remCountExplicit = mergeScalar("Remaining installments", "remainingInstallmentsCount", { it.remainingInstallmentsCount })

    val creditLimit = mergeScalar("Credit limit", "creditLimitCents", { it.creditLimitCents }, moneyDiff, moneyFmt)
    val availableCredit = mergeScalar("Available credit", "availableCreditCents", { it.availableCreditCents }, moneyDiff, moneyFmt)
    val usedCredit = mergeScalar("Used credit", "usedCreditCents", { it.usedCreditCents }, moneyDiff, moneyFmt)
    val statementBalance = mergeScalar("Statement balance", "statementBalanceCents", { it.statementBalanceCents }, moneyDiff, moneyFmt)
    val minimumDue = mergeScalar("Minimum due", "minimumDueCents", { it.minimumDueCents }, moneyDiff, moneyFmt)
    val statementDate = mergeScalar("Statement date", "statementDate", { it.statementDateIso })
    val statementPeriod = mergeScalar("Statement period", "statementPeriod", { it.statementPeriod })

    val totalRepayment = mergeScalar("Total repayment", "totalRepaymentCents", { it.totalRepaymentCents }, moneyDiff, moneyFmt)
    val interest = mergeScalar("Interest", "interestCents", { it.interestCents }, moneyDiff, moneyFmt)
    val serviceFee = mergeScalar("Service fee", "serviceFeeCents", { it.serviceFeeCents }, moneyDiff, moneyFmt)
    val processingFee = mergeScalar("Processing fee", "processingFeeCents", { it.processingFeeCents }, moneyDiff, moneyFmt)
    val otherFees = mergeScalar("Other fees", "otherFeesCents", { it.otherFeesCents }, moneyDiff, moneyFmt)
    val loanStatus = mergeScalar("Loan status", "loanStatus", { it.loanStatus })

    // Handle restructured schedule versions: prefer latest scheduleVersion while noting superseded schedule
    val latestScheduleVersion = pages.maxOfOrNull { it.scheduleVersion } ?: 1
    val scheduleEffectiveDateIso = pages.mapNotNull { it.scheduleEffectiveDateIso }.maxOrNull()
    val hasOlderScheduleVersion = pages.any { it.scheduleVersion < latestScheduleVersion }
    val activeSchedulePages = if (hasOlderScheduleVersion) {
      mergeNotes.add("Using restructured schedule v$latestScheduleVersion (supersedes earlier schedule version).")
      pages.filter { it.scheduleVersion == latestScheduleVersion }
    } else {
      pages
    }

    // Deduplicate and merge installment schedule rows in chronological order
    val mergedRows = mergeScheduleRows(activeSchedulePages.flatMap { it.installmentRows })
    val isSchedulePartial = activeSchedulePages.any { it.isSchedulePartial }
    val obligationRole = pages.map { it.obligationRole }
      .firstOrNull { it != ObligationRole.STANDALONE } ?: ObligationRole.STANDALONE

    val unpaidRows = mergedRows.filter { it.status.isUnpaidObligation }
    val paidRows = mergedRows.filter { it.status.isSettledPaid }

    val unpaidWithAmounts = unpaidRows.mapNotNull { it.amountCents }
    val calcUnpaidSum = if (unpaidWithAmounts.isNotEmpty() && unpaidWithAmounts.size == unpaidRows.size) {
      ExtractedField.calculated(
        value = MoneyFormatter.sumCentsExact(unpaidWithAmounts),
        sourceSnippet = "${unpaidRows.size} unpaid schedule rows",
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Calculated by summing ${unpaidRows.size} extracted unpaid installments."
      )
    } else if (unpaidWithAmounts.isNotEmpty()) {
      ExtractedField.calculated(
        value = MoneyFormatter.sumCentsExact(unpaidWithAmounts),
        sourceSnippet = "${unpaidWithAmounts.size} of ${unpaidRows.size} unpaid rows had amounts",
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Partial sum — some unpaid rows have no detected peso amount."
      )
    } else {
      ExtractedField.notDetected()
    }

    val paidWithAmounts = paidRows.mapNotNull { it.amountCents }
    val calcPaidSum = if (paidWithAmounts.isNotEmpty()) {
      ExtractedField.calculated(
        value = MoneyFormatter.sumCentsExact(paidWithAmounts),
        sourceSnippet = "${paidWithAmounts.size} paid schedule rows",
        confidence = if (paidWithAmounts.size == paidRows.size) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "Calculated by summing ${paidWithAmounts.size} extracted paid installments."
      )
    } else {
      ExtractedField.notDetected()
    }

    val nextDueDate = mergeScalar("Next due date", "nextDueDate", { it.nextDueDateIso })
    val finalDueDate = mergeScalar("Final due date", "finalDueDate", { it.finalDueDateIso }).let { existingFinal ->
      if (existingFinal.value != null || mergedRows.size < 2) existingFinal else {
        val lastDated = mergedRows.lastOrNull { it.dueDateIso != null }
        if (lastDated != null && lastDated.dueDateIso != nextDueDate.value) {
          ExtractedField.extracted(
            value = lastDated.dueDateIso!!,
            sourceSnippet = lastDated.sourceSnippet,
            screenshotIndex = lastDated.screenshotIndex,
            confidence = lastDated.dateConfidence,
            verificationReason = lastDated.verificationNote ?: "Final payment date from merged schedule."
          )
        } else {
          existingFinal
        }
      }
    }

    val lastPaidDate = mergeScalar("Last paid date", "lastPaidDate", { it.lastPaidDateIso }).let { existingPaid ->
      if (existingPaid.value != null || paidRows.isEmpty()) existingPaid else {
        val latestPaid = paidRows.lastOrNull { it.dueDateIso != null }
        if (latestPaid != null) {
          ExtractedField.extracted(
            value = latestPaid.dueDateIso!!,
            sourceSnippet = latestPaid.sourceSnippet,
            screenshotIndex = latestPaid.screenshotIndex,
            confidence = latestPaid.dateConfidence,
            verificationReason = "Most recent payment marked Paid across screenshots."
          )
        } else {
          existingPaid
        }
      }
    }

    val explicitFreq = pages.map { it.frequency }.firstOrNull {
      it.origin == FieldOrigin.EXTRACTED &&
        it.value != null &&
        it.value != RepaymentFrequency.UNKNOWN &&
        it.value != RepaymentFrequency.IRREGULAR
    } ?: ExtractedField.notDetected()
    val customDays = mergeScalar("Custom interval days", "customIntervalDays", { it.customIntervalDays })

    val initialDueStatus = when {
      nextDueDate.value == null -> DueDateStatus.UNKNOWN
      nextDueDate.origin == FieldOrigin.ESTIMATED -> DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
      nextDueDate.confidence == ExtractionConfidence.HIGH -> DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
      else -> DueDateStatus.UNCONFIRMED
    }

    if (pages.size > 1) {
      mergeNotes.add(0, "Combined ${pages.size} screenshots (#${pages.joinToString(", #") { "${it.screenshotIndex + 1}" }})")
    }

    val numericOrderUsed = pages.map { it.numericDateOrderUsed }
      .firstOrNull { it != NumericDateOrder.AMBIGUOUS } ?: NumericDateOrder.AMBIGUOUS

    return DebtImportDraft(
      candidateId = "loan_group_${groupIdx + 1}",
      sourceScreenshotIndices = pages.map { it.screenshotIndex }.distinct().sorted(),
      providerProfileId = providerProfileId,
      productClassification = productClassification,
      applicationProvider = applicationProvider,
      actualCreditor = actualCreditor,
      lender = lender,
      loanName = loanName,
      loanType = loanType,
      loanReferenceId = refId,
      loanBookingDateIso = loanBookingDate,
      originalPrincipalCents = originalPrincipal,
      disbursedAmountCents = disbursedAmount,
      amountReceivedCents = amountReceived,
      outstandingBalanceCents = outstandingBalance,
      amountCurrentlyDueCents = amountCurrentlyDue,
      pastDueAmountCents = pastDueAmount,
      unpaidChargesCents = unpaidCharges,
      remainingInstallmentTotalCents = remInstTotal,
      calculatedUnpaidScheduleSumCents = calcUnpaidSum,
      calculatedPaidScheduleSumCents = calcPaidSum,
      installmentAmountCents = installmentAmount,
      originalInstallmentCount = origCount,
      paidInstallmentsCount = paidCount,
      remainingInstallmentsCount = remCountExplicit,
      creditLimitCents = creditLimit,
      availableCreditCents = availableCredit,
      usedCreditCents = usedCredit,
      statementBalanceCents = statementBalance,
      minimumDueCents = minimumDue,
      statementDateIso = statementDate,
      statementPeriod = statementPeriod,
      frequency = explicitFreq,
      customIntervalDays = customDays,
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
      isSchedulePartial = isSchedulePartial,
      scheduleVersion = latestScheduleVersion,
      scheduleEffectiveDateIso = scheduleEffectiveDateIso,
      obligationRole = obligationRole,
      mergeNotes = mergeNotes.distinct(),
      validationIssues = extraIssues.distinctBy { Triple(it.fieldKey, it.severity, it.title) },
      detectedLabelCues = pages.flatMap { it.detectedLabelCues },
      textCorrections = pages.flatMap { it.textCorrections },
      numericDateOrderUsed = numericOrderUsed
    )
  }

  private fun mergeLenderField(pages: List<SingleScreenshotExtraction>): ExtractedField<String> {
    val withLender = pages.map { it.lender }.filter { !it.value.isNullOrBlank() }
    if (withLender.isEmpty()) return ExtractedField.notDetected()
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
      (a.dateConfidence.rank <= b.dateConfidence.rank && a.amountCents != null)
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

  private fun lendersCompatible(a: String, b: String): Boolean {
    val normA = normalizeText(a)
    val normB = normalizeText(b)
    if (normA == normB) return true
    val prefixes = listOf("shopee", "gcash", "maya", "lazada", "billease", "atome", "cimb", "skyro", "home credit", "tala")
    return prefixes.any { prefix ->
      (normA == prefix && normB.startsWith("$prefix ")) ||
        (normB == prefix && normA.startsWith("$prefix "))
    }
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

  private fun <T> ExtractedField<T>.extractedOnlyOrNull(): T? =
    if (origin == FieldOrigin.EXTRACTED) value else null
}
