package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.InterestCalculationMethod
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.PrepaymentRuleType
import com.baonplan.finance.domain.model.ProjectionReliability
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.model.ScheduleCompletenessStatus
import com.baonplan.finance.domain.model.SourceDocumentKind
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow

data class ScheduleAnalysisResult(
  val mergedRows: List<ExtractedInstallmentRow>,
  val scheduleVersions: List<ScheduleVersionSnapshot>,
  val contractualTerms: ContractualTermsSummary,
  val payoffTimeline: PayoffTimelineProjection,
  val analyzedDocuments: List<AnalyzedDocumentSummary>,
  val evidenceTraces: List<DocumentEvidenceTrace>,
  val isSchedulePartial: Boolean
)

/**
 * Pure-Kotlin deterministic repayment schedule reconstruction, evidence-hierarchy reconciler,
 * and payoff timeline projection engine.
 *
 * Never invents missing schedules or fixed payoff dates for revolving credit or incomplete data.
 * Uses exact Long centavo / BigDecimal arithmetic throughout.
 */
object RepaymentScheduleAnalyzer {

  fun contractualAuthorityRank(kind: SourceDocumentKind): Int = when (kind) {
    SourceDocumentKind.DISCLOSURE_STATEMENT -> 1
    SourceDocumentKind.LOAN_AGREEMENT -> 1
    SourceDocumentKind.AMORTIZATION_SCHEDULE -> 2
    SourceDocumentKind.RESTRUCTURING_NOTICE -> 3
    SourceDocumentKind.BILLING_STATEMENT -> 4
    SourceDocumentKind.PAYMENT_RECEIPT -> 5
    SourceDocumentKind.APP_SCHEDULE_SCREENSHOT,
    SourceDocumentKind.APP_SUMMARY_SCREENSHOT,
    SourceDocumentKind.UNKNOWN_DOCUMENT -> 6
  }

  fun paymentStatusAuthorityRank(kind: SourceDocumentKind): Int = when (kind) {
    SourceDocumentKind.BILLING_STATEMENT -> 1
    SourceDocumentKind.PAYMENT_RECEIPT -> 1
    SourceDocumentKind.AMORTIZATION_SCHEDULE -> 2
    SourceDocumentKind.RESTRUCTURING_NOTICE -> 2
    SourceDocumentKind.APP_SCHEDULE_SCREENSHOT,
    SourceDocumentKind.APP_SUMMARY_SCREENSHOT,
    SourceDocumentKind.UNKNOWN_DOCUMENT -> 3
    SourceDocumentKind.DISCLOSURE_STATEMENT,
    SourceDocumentKind.LOAN_AGREEMENT -> 4
  }

  fun analyzeGroup(
    pages: List<SingleScreenshotExtraction>,
    draft: DebtImportDraft
  ): ScheduleAnalysisResult {
    val sortedPages = pages.sortedBy { it.screenshotIndex }
    val pageKindByIdx = sortedPages.associate { it.screenshotIndex to it.sourceDocumentKind }

    val mergedContractual = mergeContractualTerms(sortedPages)
    val analyzedDocs = buildAnalyzedDocuments(sortedPages)

    // Group pages by scheduleVersion (v1 original vs v2 restructured/revised)
    val pagesByVersion = sortedPages.groupBy { it.scheduleVersion }.toSortedMap()
    val latestVersion = pagesByVersion.keys.maxOrNull() ?: 1

    val versionSnapshots = mutableListOf<ScheduleVersionSnapshot>()
    var activeMergedRows: List<ExtractedInstallmentRow> = emptyList()

    for ((versionNum, versionPages) in pagesByVersion) {
      val versionRows = reconcileVersionRows(
        versionPages = versionPages,
        pageKindByIdx = pageKindByIdx
      )
      val isCurrent = versionNum == latestVersion
      if (isCurrent) {
        activeMergedRows = versionRows
      }
      val versionEntries = toReconstructedEntries(versionRows, versionNum)
      val verInstAmt = versionPages.firstNotNullOfOrNull { it.installmentAmountCents.value }
        ?: versionRows.firstNotNullOfOrNull { it.amountCents }
      val verOrigCount = versionPages.firstNotNullOfOrNull { it.originalInstallmentCount.value }
        ?: versionRows.size.takeIf { it > 0 && !versionPages.any { p -> p.isSchedulePartial } }
      val verMaturity = if (versionRows.isNotEmpty() && !versionPages.any { p -> p.isSchedulePartial }) {
        listOfNotNull(
          versionPages.mapNotNull { it.finalDueDateIso.value }.maxOrNull(),
          versionRows.lastOrNull { it.dueDateIso != null }?.dueDateIso
        ).maxOrNull()
      } else {
        versionPages.mapNotNull { it.finalDueDateIso.value }.maxOrNull()
          ?: versionRows.lastOrNull { it.dueDateIso != null }?.dueDateIso
      }
      val verEffDate = versionPages.mapNotNull { it.scheduleEffectiveDateIso }.maxOrNull()
      val verReason = versionPages.mapNotNull { it.modificationReason }.firstOrNull()

      versionSnapshots.add(
        ScheduleVersionSnapshot(
          versionNumber = versionNum,
          effectiveDateIso = verEffDate,
          modificationReason = verReason,
          installmentAmountCents = verInstAmt,
          totalInstallmentCount = verOrigCount,
          maturityDateIso = verMaturity,
          isCurrentlyEffective = isCurrent,
          entries = versionEntries
        )
      )
    }

    val activePages = pagesByVersion[latestVersion] ?: sortedPages
    val origCount = draft.originalInstallmentCount.value
    val paidRowsCount = activeMergedRows.count { it.status.isSettledPaid }
    val unpaidRowsCount = activeMergedRows.count { it.status.isUnpaidObligation }

    val isSchedulePartial = when {
      origCount != null && activeMergedRows.size >= origCount -> false
      origCount != null && activeMergedRows.isNotEmpty() && activeMergedRows.size < origCount -> true
      draft.paidInstallmentsCount.value != null && paidRowsCount < draft.paidInstallmentsCount.value -> true
      draft.remainingInstallmentsCount.value != null && unpaidRowsCount > 0 && unpaidRowsCount < draft.remainingInstallmentsCount.value -> true
      else -> activePages.any { it.isSchedulePartial }
    }

    val payoffTimeline = buildPayoffTimeline(
      draft = draft,
      activeRows = activeMergedRows,
      versionSnapshots = versionSnapshots,
      contractualTerms = mergedContractual,
      isSchedulePartial = isSchedulePartial,
      sortedPages = sortedPages
    )

    val evidenceTraces = buildEvidenceTraces(
      sortedPages = sortedPages,
      draft = draft,
      contractualTerms = mergedContractual,
      payoffTimeline = payoffTimeline
    )

    return ScheduleAnalysisResult(
      mergedRows = activeMergedRows,
      scheduleVersions = versionSnapshots,
      contractualTerms = mergedContractual,
      payoffTimeline = payoffTimeline,
      analyzedDocuments = analyzedDocs,
      evidenceTraces = evidenceTraces,
      isSchedulePartial = isSchedulePartial
    )
  }

  private fun reconcileVersionRows(
    versionPages: List<SingleScreenshotExtraction>,
    pageKindByIdx: Map<Int, SourceDocumentKind>
  ): List<ExtractedInstallmentRow> {
    val allRows = versionPages.flatMap { it.installmentRows }
    if (allRows.isEmpty()) return emptyList()

    val merged = mutableListOf<ExtractedInstallmentRow>()
    for (row in allRows) {
      val matchIdx = merged.indexOfFirst { existing ->
        areSameInstallmentEntry(existing, row)
      }
      if (matchIdx == -1) {
        merged.add(row)
      } else {
        merged[matchIdx] = reconcileTwoRows(merged[matchIdx], row, pageKindByIdx)
      }
    }

    val sorted = merged.sortedWith(
      compareBy<ExtractedInstallmentRow> { it.dueDateIso ?: "9999-12-31" }
        .thenBy { it.installmentNumberHint ?: Int.MAX_VALUE }
        .thenBy { it.screenshotIndex }
    )

    // Assign clean sequential installment numbers when dates are strictly ordered and hints are missing
    val allDated = sorted.all { it.dueDateIso != null }
    val hasExplicitNumbers = sorted.any { it.installmentNumberHint != null }
    return if (allDated && !hasExplicitNumbers && sorted.size >= 2) {
      sorted.mapIndexed { idx, r -> r.copy(installmentNumberHint = idx + 1) }
    } else {
      sorted
    }
  }

  private fun areSameInstallmentEntry(a: ExtractedInstallmentRow, b: ExtractedInstallmentRow): Boolean {
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

  private fun reconcileTwoRows(
    a: ExtractedInstallmentRow,
    b: ExtractedInstallmentRow,
    pageKindByIdx: Map<Int, SourceDocumentKind>
  ): ExtractedInstallmentRow {
    val kindA = pageKindByIdx[a.screenshotIndex] ?: SourceDocumentKind.UNKNOWN_DOCUMENT
    val kindB = pageKindByIdx[b.screenshotIndex] ?: SourceDocumentKind.UNKNOWN_DOCUMENT

    val contractRankA = contractualAuthorityRank(kindA)
    val contractRankB = contractualAuthorityRank(kindB)
    val paymentRankA = paymentStatusAuthorityRank(kindA)
    val paymentRankB = paymentStatusAuthorityRank(kindB)

    // Payment status follows payment evidence hierarchy (Statement/Receipt > Schedule > App > Pre-loan Disclosure)
    val betterStatus = when {
      a.status == ExtractedInstallmentStatus.PAID || b.status == ExtractedInstallmentStatus.PAID ->
        ExtractedInstallmentStatus.PAID
      a.status == ExtractedInstallmentStatus.REFUNDED || b.status == ExtractedInstallmentStatus.REFUNDED ->
        ExtractedInstallmentStatus.REFUNDED
      a.status == ExtractedInstallmentStatus.REVERSED || b.status == ExtractedInstallmentStatus.REVERSED ->
        ExtractedInstallmentStatus.REVERSED
      a.status == ExtractedInstallmentStatus.DISPUTED || b.status == ExtractedInstallmentStatus.DISPUTED ->
        ExtractedInstallmentStatus.DISPUTED
      a.status == ExtractedInstallmentStatus.PARTIALLY_PAID || b.status == ExtractedInstallmentStatus.PARTIALLY_PAID ->
        ExtractedInstallmentStatus.PARTIALLY_PAID
      a.status == ExtractedInstallmentStatus.OVERDUE || b.status == ExtractedInstallmentStatus.OVERDUE ->
        ExtractedInstallmentStatus.OVERDUE
      paymentRankA < paymentRankB && a.status != ExtractedInstallmentStatus.UNKNOWN -> a.status
      paymentRankB < paymentRankA && b.status != ExtractedInstallmentStatus.UNKNOWN -> b.status
      a.status != ExtractedInstallmentStatus.UNKNOWN -> a.status
      else -> b.status
    }

    // Amount & breakdown components prefer higher contractual authority when both have amounts
    val preferContractA = when {
      a.amountCents != null && b.amountCents == null -> true
      b.amountCents != null && a.amountCents == null -> false
      contractRankA != contractRankB -> contractRankA < contractRankB
      else -> a.dateConfidence.rank <= b.dateConfidence.rank
    }
    val base = if (preferContractA) a else b
    val other = if (preferContractA) b else a

    return base.copy(
      installmentNumberHint = base.installmentNumberHint ?: other.installmentNumberHint,
      dueDateIso = base.dueDateIso ?: other.dueDateIso,
      amountCents = base.amountCents ?: other.amountCents,
      principalComponentCents = base.principalComponentCents ?: other.principalComponentCents,
      interestComponentCents = base.interestComponentCents ?: other.interestComponentCents,
      serviceFeeComponentCents = base.serviceFeeComponentCents ?: other.serviceFeeComponentCents,
      otherChargesComponentCents = base.otherChargesComponentCents ?: other.otherChargesComponentCents,
      amountPaidCents = maxOf(a.amountPaidCents ?: 0L, b.amountPaidCents ?: 0L).takeIf { it > 0L },
      actualPaymentDateIso = base.actualPaymentDateIso ?: other.actualPaymentDateIso,
      remainingAmountCents = base.remainingAmountCents ?: other.remainingAmountCents,
      remainingPrincipalAfterCents = base.remainingPrincipalAfterCents ?: other.remainingPrincipalAfterCents,
      status = betterStatus,
      evidenceSourceLabel = base.evidenceSourceLabel ?: other.evidenceSourceLabel,
      rawStatusLabel = base.rawStatusLabel ?: other.rawStatusLabel,
      verificationNote = base.verificationNote ?: other.verificationNote
    )
  }

  private fun toReconstructedEntries(
    rows: List<ExtractedInstallmentRow>,
    versionNumber: Int
  ): List<ReconstructedScheduleEntry> = rows.mapIndexed { idx, r ->
    val totalCents = r.amountCents
    val paidCents = when {
      r.amountPaidCents != null -> r.amountPaidCents
      r.status.isSettledPaid && totalCents != null -> totalCents
      else -> 0L
    }
    val remCents = r.remainingAmountCents ?: if (totalCents != null) {
      (totalCents - paidCents).coerceAtLeast(0L)
    } else {
      null
    }
    ReconstructedScheduleEntry(
      installmentNumber = r.installmentNumberHint ?: (idx + 1),
      dueDateIso = r.dueDateIso,
      principalComponentCents = r.principalComponentCents,
      interestComponentCents = r.interestComponentCents,
      serviceFeeComponentCents = r.serviceFeeComponentCents,
      otherChargesComponentCents = r.otherChargesComponentCents,
      totalInstallmentCents = totalCents,
      amountPaidCents = paidCents,
      actualPaymentDateIso = r.actualPaymentDateIso ?: if (r.status.isSettledPaid) r.dueDateIso else null,
      remainingAmountCents = remCents,
      remainingPrincipalAfterCents = r.remainingPrincipalAfterCents,
      statusLabel = r.rawStatusLabel ?: r.status.label,
      isPaid = r.status.isSettledPaid,
      isOverdue = r.status == ExtractedInstallmentStatus.OVERDUE,
      isPartiallyPaid = r.status == ExtractedInstallmentStatus.PARTIALLY_PAID,
      isCancelledOrReversed = r.status.isCancelledOrReversed,
      scheduleVersion = versionNumber,
      evidenceSourceLabel = r.evidenceSourceLabel ?: "Source #${r.screenshotIndex + 1}",
      pageNumber = r.pageNumber,
      note = r.verificationNote
    )
  }

  private fun mergeContractualTerms(pages: List<SingleScreenshotExtraction>): ContractualTermsSummary {
    if (pages.isEmpty()) return ContractualTermsSummary()
    val ranked = pages.sortedWith(
      compareBy<SingleScreenshotExtraction> { contractualAuthorityRank(it.sourceDocumentKind) }
        .thenBy { it.screenshotIndex }
    )
    return ContractualTermsSummary(
      annualInterestRatePercent = ranked.firstNotNullOfOrNull { it.contractualTerms.annualInterestRatePercent },
      monthlyInterestRatePercent = ranked.firstNotNullOfOrNull { it.contractualTerms.monthlyInterestRatePercent },
      dailyInterestRatePercent = ranked.firstNotNullOfOrNull { it.contractualTerms.dailyInterestRatePercent },
      effectiveInterestRatePercent = ranked.firstNotNullOfOrNull { it.contractualTerms.effectiveInterestRatePercent },
      interestCalculationMethod = ranked
        .map { it.contractualTerms.interestCalculationMethod }
        .firstOrNull { it != InterestCalculationMethod.UNKNOWN }
        ?: InterestCalculationMethod.UNKNOWN,
      feesIncludedInInstallment = ranked.firstNotNullOfOrNull { it.contractualTerms.feesIncludedInInstallment },
      feesDeductedUpfront = ranked.firstNotNullOfOrNull { it.contractualTerms.feesDeductedUpfront },
      gracePeriodDays = ranked.firstNotNullOfOrNull { it.contractualTerms.gracePeriodDays },
      latePaymentFeeRule = ranked.firstNotNullOfOrNull { it.contractualTerms.latePaymentFeeRule },
      prepaymentRule = ranked
        .map { it.contractualTerms.prepaymentRule }
        .firstOrNull { it != PrepaymentRuleType.UNKNOWN }
        ?: PrepaymentRuleType.UNKNOWN,
      prepaymentRuleDetail = ranked.firstNotNullOfOrNull { it.contractualTerms.prepaymentRuleDetail },
      paymentAllocationOrder = ranked.firstNotNullOfOrNull { it.contractualTerms.paymentAllocationOrder },
      dayCountConvention = ranked.firstNotNullOfOrNull { it.contractualTerms.dayCountConvention },
      interestAccrualStartDateIso = ranked.firstNotNullOfOrNull { it.contractualTerms.interestAccrualStartDateIso }
    )
  }

  private fun buildAnalyzedDocuments(pages: List<SingleScreenshotExtraction>): List<AnalyzedDocumentSummary> {
    val grouped = pages.groupBy { it.sourceDocumentId ?: "page_${it.screenshotIndex}" }
    return grouped.map { (docId, docPages) ->
      val first = docPages.first()
      val bestKind = docPages
        .map { it.sourceDocumentKind }
        .minByOrNull { contractualAuthorityRank(it) }
        ?: SourceDocumentKind.UNKNOWN_DOCUMENT
      val isPdf = first.sourceDocumentId != null || first.totalPagesInDocument > 1
      AnalyzedDocumentSummary(
        documentId = docId,
        displayTitle = first.documentTitle?.takeIf { it.isNotBlank() }
          ?: if (isPdf) "Loan Document PDF" else "Screenshot #${first.screenshotIndex + 1}",
        documentKind = bestKind,
        pageCount = maxOf(first.totalPagesInDocument, docPages.size),
        pagesRead = docPages.size,
        documentDateIso = docPages.mapNotNull { it.documentDateIso ?: it.statementDateIso.value }.maxOrNull(),
        detectedReferenceId = docPages.firstNotNullOfOrNull { it.loanReferenceId.value },
        isPdf = isPdf,
        usedOcrFallback = docPages.any { (it.averageOcrConfidence ?: 1f) < 0.98f && isPdf }
      )
    }
  }

  private fun buildPayoffTimeline(
    draft: DebtImportDraft,
    activeRows: List<ExtractedInstallmentRow>,
    versionSnapshots: List<ScheduleVersionSnapshot>,
    contractualTerms: ContractualTermsSummary,
    isSchedulePartial: Boolean,
    sortedPages: List<SingleScreenshotExtraction>
  ): PayoffTimelineProjection {
    val productClass = draft.productClassification.value
    val isRevolving = productClass == LoanProductClassification.REVOLVING_CREDIT
    val origLoanDate = draft.loanBookingDateIso.value ?: contractualTerms.interestAccrualStartDateIso
    val firstInstallmentDate = activeRows.firstOrNull { it.dueDateIso != null }?.dueDateIso
    val nextPayDate = draft.nextDueDateIso.value
      ?: activeRows.firstOrNull { it.status.isUnpaidObligation && it.dueDateIso != null }?.dueDateIso

    val origCount = draft.originalInstallmentCount.value
    val rowPaidCount = activeRows.count { it.status.isSettledPaid }.takeIf { it > 0 }
    val rowUnpaidCount = activeRows.count { it.status.isUnpaidObligation }.takeIf { it > 0 }
    val paidCount = if (!isSchedulePartial && rowPaidCount != null) {
      rowPaidCount
    } else {
      draft.paidInstallmentsCount.value ?: rowPaidCount
    }
    val remCount = if (!isSchedulePartial && rowUnpaidCount != null) {
      rowUnpaidCount
    } else {
      draft.remainingInstallmentsCount.value
        ?: if (origCount != null && paidCount != null && origCount >= paidCount) origCount - paidCount else null
    }

    val remTotalCents = if (!isSchedulePartial && draft.calculatedUnpaidScheduleSumCents.value != null) {
      draft.calculatedUnpaidScheduleSumCents.value
    } else {
      draft.remainingInstallmentTotalCents.value
        ?: draft.calculatedUnpaidScheduleSumCents.value
        ?: draft.outstandingBalanceCents.value
    }

    val sourceSummary = sortedPages
      .map { p -> p.documentTitle?.takeIf { it.isNotBlank() } ?: p.sourceDocumentKind.label }
      .distinct()
      .joinToString(" + ")

    if (isRevolving) {
      return PayoffTimelineProjection(
        originalLoanDateIso = origLoanDate,
        firstInstallmentDateIso = firstInstallmentDate,
        nextPaymentDateIso = nextPayDate,
        contractualMaturityDateIso = null,
        currentScheduledPayoffDateIso = null,
        projectedPayoffDateIso = null,
        actualFullyPaidDateIso = null,
        totalInstallmentsCount = null,
        paidInstallmentsCount = null,
        remainingInstallmentsCount = null,
        remainingScheduledTotalCents = draft.statementBalanceCents.value ?: draft.outstandingBalanceCents.value,
        completenessStatus = ScheduleCompletenessStatus.REVOLVING_NO_FIXED_PAYOFF,
        reliability = ProjectionReliability.NOT_APPLICABLE_REVOLVING,
        scheduleSourceSummary = sourceSummary,
        missingInformationExplanation = "Revolving credit lines do not have a fixed full-payment date because balances change with new usage and minimum payments."
      )
    }

    val hasUnpaidObligation = activeRows.any { it.status.isUnpaidObligation } ||
      (draft.outstandingBalanceCents.value != null && draft.outstandingBalanceCents.value > 0L)
    val isSettledPaid = !hasUnpaidObligation && (
      draft.loanStatus.value == DebtStatus.PAID ||
        (draft.outstandingBalanceCents.value != null && draft.outstandingBalanceCents.value == 0L)
      )

    val v1Snapshot = versionSnapshots.firstOrNull { it.versionNumber == 1 }
    val latestSnapshot = versionSnapshots.lastOrNull()
    val activeRowsLastDate = if (!isSchedulePartial) {
      activeRows.lastOrNull { it.dueDateIso != null }?.dueDateIso
    } else null

    val contractualMaturity = v1Snapshot?.maturityDateIso
      ?: activeRowsLastDate
      ?: draft.finalDueDateIso.value

    val currentScheduledMaturity = latestSnapshot?.maturityDateIso
      ?: activeRowsLastDate
      ?: draft.finalDueDateIso.value

    if (isSettledPaid) {
      val settledDate = draft.lastPaidDateIso.value
        ?: activeRows.lastOrNull { it.status.isSettledPaid && it.dueDateIso != null }?.dueDateIso
        ?: draft.statementDateIso.value
      return PayoffTimelineProjection(
        originalLoanDateIso = origLoanDate,
        firstInstallmentDateIso = firstInstallmentDate,
        nextPaymentDateIso = null,
        contractualMaturityDateIso = contractualMaturity,
        currentScheduledPayoffDateIso = currentScheduledMaturity,
        projectedPayoffDateIso = settledDate,
        actualFullyPaidDateIso = settledDate,
        totalInstallmentsCount = origCount,
        paidInstallmentsCount = paidCount ?: origCount,
        remainingInstallmentsCount = 0,
        remainingScheduledTotalCents = 0L,
        completenessStatus = if (activeRows.isNotEmpty() && !isSchedulePartial) {
          ScheduleCompletenessStatus.COMPLETE_SCHEDULE
        } else {
          ScheduleCompletenessStatus.SUMMARY_ONLY_NO_SCHEDULE
        },
        reliability = ProjectionReliability.CONFIRMED_BY_SCHEDULE,
        scheduleSourceSummary = sourceSummary
      )
    }

    val assumptions = mutableListOf<String>()
    var projectedDate: String? = null
    var reliability = ProjectionReliability.INSUFFICIENT_DATA
    var missingExplanation: String? = null

    val completeness = when {
      activeRows.isNotEmpty() && !isSchedulePartial &&
        (origCount == null || activeRows.size >= origCount) ->
        ScheduleCompletenessStatus.COMPLETE_SCHEDULE
      activeRows.isNotEmpty() ->
        ScheduleCompletenessStatus.PARTIAL_SCHEDULE
      origCount != null || remCount != null || nextPayDate != null ->
        ScheduleCompletenessStatus.SUMMARY_ONLY_NO_SCHEDULE
      else ->
        ScheduleCompletenessStatus.NEEDS_USER_REVIEW
    }

    if (completeness == ScheduleCompletenessStatus.COMPLETE_SCHEDULE && currentScheduledMaturity != null) {
      projectedDate = currentScheduledMaturity
      reliability = ProjectionReliability.CONFIRMED_BY_SCHEDULE
    } else if (currentScheduledMaturity != null &&
      draft.finalDueDateIso.value != null &&
      draft.finalDueDateIso.origin == FieldOrigin.EXTRACTED
    ) {
      projectedDate = currentScheduledMaturity
      reliability = ProjectionReliability.CONFIRMED_BY_SCHEDULE
    } else {
      val freq = draft.frequency.value
      val startNext = ManilaTime.parseDate(nextPayDate)
      val isSingleBillPayLater = draft.productClassification.value == LoanProductClassification.BNPL_PAY_LATER &&
        activeRows.isEmpty() &&
        remCount == null &&
        nextPayDate != null &&
        draft.amountCurrentlyDueCents.value != null

      if (startNext != null &&
        remCount != null &&
        remCount >= 1 &&
        freq != null &&
        freq != RepaymentFrequency.UNKNOWN &&
        freq != RepaymentFrequency.IRREGULAR
      ) {
        val anchorStart = inferAnchorStart(startNext, activeRows, freq)
        val projectedDates = ManilaTime.generateProjectedScheduleDates(
          nextDueDate = startNext,
          count = remCount,
          frequency = freq,
          customIntervalDays = draft.customIntervalDays.value,
          adjustHolidays = true,
          anchorStart = anchorStart
        )
        val finalProjected = projectedDates.lastOrNull()
        if (finalProjected != null && projectedDates.size == remCount) {
          projectedDate = ManilaTime.formatIso(finalProjected)
          reliability = ProjectionReliability.ESTIMATED_FROM_PARTIAL
          assumptions.add(
            "Projected from next payment date (${ManilaTime.formatDisplay(nextPayDate)}) across $remCount remaining ${freq.label.lowercase()} payment(s)."
          )
          missingExplanation = if (origCount != null && activeRows.isNotEmpty()) {
            "Showing ${activeRows.size} of $origCount installment rows; remaining dates are projected from the ${freq.label.lowercase()} interval."
          } else {
            "Full installment schedule was not included; payoff date is projected from remaining installments and payment frequency."
          }
        } else {
          missingExplanation = "Payment interval (${freq.label}) requires the full schedule or specific due dates to determine the final payoff date."
        }
      } else if (isSingleBillPayLater) {
        projectedDate = nextPayDate
        reliability = ProjectionReliability.ESTIMATED_FROM_PARTIAL
        assumptions.add("Full current bill due ${ManilaTime.formatDisplay(nextPayDate)}.")
      } else {
        missingExplanation = when {
          freq == RepaymentFrequency.IRREGULAR ->
            "Irregular payment schedule has missing future dates — upload the remaining schedule or agreement to determine the full-payment date."
          nextPayDate == null ->
            "Next payment due date was not detected — confirm the next due date or upload a full repayment schedule."
          remCount == null ->
            "Remaining installment count was not detected — confirm payments left or upload the full schedule."
          else ->
            "Payment frequency is unknown — select payment frequency or upload the full schedule to project the payoff date."
        }
      }
    }

    return PayoffTimelineProjection(
      originalLoanDateIso = origLoanDate,
      firstInstallmentDateIso = firstInstallmentDate,
      nextPaymentDateIso = nextPayDate,
      contractualMaturityDateIso = contractualMaturity,
      currentScheduledPayoffDateIso = currentScheduledMaturity,
      projectedPayoffDateIso = projectedDate,
      actualFullyPaidDateIso = null,
      totalInstallmentsCount = origCount,
      paidInstallmentsCount = paidCount,
      remainingInstallmentsCount = remCount,
      remainingScheduledTotalCents = remTotalCents,
      completenessStatus = completeness,
      reliability = reliability,
      scheduleSourceSummary = sourceSummary,
      missingInformationExplanation = missingExplanation,
      assumptionsUsed = assumptions
    )
  }

  private fun inferAnchorStart(
    startNext: LocalDate,
    activeRows: List<ExtractedInstallmentRow>,
    freq: RepaymentFrequency
  ): LocalDate {
    if (freq == RepaymentFrequency.WEEKLY || freq == RepaymentFrequency.EVERY_14_DAYS) {
      val rowDates = activeRows.mapNotNull { ManilaTime.parseDate(it.dueDateIso) }
      val dominantDay = rowDates
        .filter { d -> !ManilaTime.isPhilippineRegularHoliday(d.minusDays(1)) }
        .groupingBy { it.dayOfWeek }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
      if (dominantDay != null && startNext.dayOfWeek != dominantDay &&
        ManilaTime.isPhilippineRegularHoliday(startNext.minusDays(1))
      ) {
        return startNext.minusDays(1)
      }
    }
    return ManilaTime.inferUnshiftedAnchorDate(startNext)
  }

  private fun buildEvidenceTraces(
    sortedPages: List<SingleScreenshotExtraction>,
    draft: DebtImportDraft,
    contractualTerms: ContractualTermsSummary,
    payoffTimeline: PayoffTimelineProjection
  ): List<DocumentEvidenceTrace> {
    val traces = mutableListOf<DocumentEvidenceTrace>()
    val pageByIdx = sortedPages.associateBy { it.screenshotIndex }

    fun describeSource(idx: Int?): Pair<String, SourceDocumentKind> {
      val page = idx?.let { pageByIdx[it] } ?: sortedPages.firstOrNull()
      if (page == null) return "Uploaded source" to SourceDocumentKind.UNKNOWN_DOCUMENT
      val title = page.documentTitle?.takeIf { it.isNotBlank() }
        ?: "${page.sourceDocumentKind.label} #${page.screenshotIndex + 1}"
      val suffix = if (page.isPdfPage && page.totalPagesInDocument > 1) " (Page ${page.pageNumber})" else ""
      return (title + suffix) to page.sourceDocumentKind
    }

    draft.originalPrincipalCents.value?.let { cents ->
      val (src, kind) = describeSource(draft.originalPrincipalCents.sourceScreenshotIndex)
      traces.add(
        DocumentEvidenceTrace(
          fieldLabel = "Original loan amount",
          resolvedValueDisplay = MoneyFormatter.formatPeso(cents),
          sourceDocumentLabel = src,
          pageNumber = draft.originalPrincipalCents.sourceScreenshotIndex?.let { pageByIdx[it]?.pageNumber },
          documentKind = kind,
          explanation = draft.originalPrincipalCents.verificationReason ?: "Extracted from $src."
        )
      )
    }

    draft.originalInstallmentCount.value?.let { count ->
      val (src, kind) = describeSource(draft.originalInstallmentCount.sourceScreenshotIndex)
      traces.add(
        DocumentEvidenceTrace(
          fieldLabel = "Contractual term",
          resolvedValueDisplay = "$count installments",
          sourceDocumentLabel = src,
          pageNumber = draft.originalInstallmentCount.sourceScreenshotIndex?.let { pageByIdx[it]?.pageNumber },
          documentKind = kind,
          explanation = draft.originalInstallmentCount.verificationReason ?: "Read from $src."
        )
      )
    }

    val rateDisplay = listOfNotNull(
      contractualTerms.annualInterestRatePercent?.let { "$it% p.a." },
      contractualTerms.monthlyInterestRatePercent?.let { "$it% monthly" },
      contractualTerms.dailyInterestRatePercent?.let { "$it% daily" },
      contractualTerms.effectiveInterestRatePercent?.let { "EIR $it%" }
    ).joinToString(" · ")
    if (rateDisplay.isNotBlank()) {
      val contractPage = sortedPages.minByOrNull { contractualAuthorityRank(it.sourceDocumentKind) }
      val (src, kind) = describeSource(contractPage?.screenshotIndex)
      traces.add(
        DocumentEvidenceTrace(
          fieldLabel = "Interest rate & method",
          resolvedValueDisplay = if (contractualTerms.interestCalculationMethod != InterestCalculationMethod.UNKNOWN) {
            "$rateDisplay (${contractualTerms.interestCalculationMethod.label})"
          } else {
            rateDisplay
          },
          sourceDocumentLabel = src,
          pageNumber = contractPage?.pageNumber,
          documentKind = kind,
          explanation = "Extracted from contractual terms in $src."
        )
      )
    }

    if (payoffTimeline.paidInstallmentsCount != null || payoffTimeline.remainingInstallmentsCount != null) {
      val statusPage = sortedPages.minByOrNull { paymentStatusAuthorityRank(it.sourceDocumentKind) }
      val (src, kind) = describeSource(statusPage?.screenshotIndex)
      val progressText = buildString {
        if (payoffTimeline.paidInstallmentsCount != null) append("${payoffTimeline.paidInstallmentsCount} paid")
        if (payoffTimeline.remainingInstallmentsCount != null) {
          if (isNotEmpty()) append(" · ")
          append("${payoffTimeline.remainingInstallmentsCount} remaining")
        }
      }
      traces.add(
        DocumentEvidenceTrace(
          fieldLabel = "Repayment progress",
          resolvedValueDisplay = progressText,
          sourceDocumentLabel = src,
          pageNumber = statusPage?.pageNumber,
          documentKind = kind,
          explanation = "Reconciled using latest payment evidence from $src."
        )
      )
    }

    payoffTimeline.projectedPayoffDateIso?.let { payoffIso ->
      val (src, kind) = describeSource(sortedPages.lastOrNull()?.screenshotIndex)
      traces.add(
        DocumentEvidenceTrace(
          fieldLabel = "Projected full-payment date",
          resolvedValueDisplay = ManilaTime.formatDisplay(payoffIso),
          sourceDocumentLabel = src,
          documentKind = kind,
          explanation = payoffTimeline.assumptionsUsed.firstOrNull()
            ?: "Determined from ${payoffTimeline.completenessStatus.label.lowercase()}."
        )
      )
    }

    return traces
  }

  /**
   * Computes clearly labeled hypothetical payoff scenarios without modifying stored debt records.
   */
  fun computeHypotheticalScenarios(
    remainingBalanceCents: Long,
    installmentAmountCents: Long,
    remainingInstallmentsCount: Int,
    nextDueDateIso: String?,
    frequency: RepaymentFrequency,
    customIntervalDays: Int? = null,
    contractualTerms: ContractualTermsSummary = ContractualTermsSummary()
  ): List<HypotheticalPayoffScenario> {
    if (remainingBalanceCents <= 0L) return emptyList()
    val scenarios = mutableListOf<HypotheticalPayoffScenario>()

    // 1. Early full settlement scenario
    val earlySettlementNote = when (contractualTerms.prepaymentRule) {
      PrepaymentRuleType.REBATE_UNEARNED_INTEREST ->
        "Contract allows unearned interest rebate upon early settlement — check lender's exact payoff quote."
      PrepaymentRuleType.NO_PENALTY_REMAINING_PRINCIPAL ->
        "No prepayment penalty stated — settling remaining principal clears the loan early."
      PrepaymentRuleType.EARLY_TERMINATION_FEE,
      PrepaymentRuleType.PREPAYMENT_PENALTY ->
        "Early termination fee may apply (${contractualTerms.prepaymentRuleDetail ?: "per agreement"})."
      PrepaymentRuleType.FULL_CONTRACT_INTEREST_DUE,
      PrepaymentRuleType.NO_EARLY_SETTLEMENT_REBATE ->
        "Contract states full scheduled interest applies even on early settlement."
      PrepaymentRuleType.UNKNOWN ->
        "Prepayment rebate/fee terms not stated in uploaded documents — confirm with lender before paying off early."
    }
    scenarios.add(
      HypotheticalPayoffScenario(
        scenarioTitle = "Hypothetical: Full early payoff today",
        isHypothetical = true,
        projectedPayoffDateIso = ManilaTime.formatIso(ManilaTime.today()),
        estimatedTotalPaidCents = remainingBalanceCents,
        estimatedInterestSavedCents = null,
        explanation = "Paying ${MoneyFormatter.formatPeso(remainingBalanceCents)} today settles the tracked balance immediately. $earlySettlementNote"
      )
    )

    // 2. Extra payment per cycle scenario (if regular frequency and >= 3 installments left)
    val startNext = ManilaTime.parseDate(nextDueDateIso)
    if (startNext != null &&
      installmentAmountCents > 0L &&
      remainingInstallmentsCount >= 3 &&
      frequency != RepaymentFrequency.UNKNOWN &&
      frequency != RepaymentFrequency.IRREGULAR &&
      frequency != RepaymentFrequency.ONE_TIME
    ) {
      val extraPerCycleCents = (installmentAmountCents / 2L).coerceAtLeast(10_000L) // +50% or +₱100
      val acceleratedCyclePayment = installmentAmountCents + extraPerCycleCents
      val newPaymentsNeeded = MoneyFormatter.divideCentsCeil(remainingBalanceCents, acceleratedCyclePayment).toInt().coerceAtLeast(1)
      if (newPaymentsNeeded < remainingInstallmentsCount) {
        var cursor: LocalDate? = startNext
        for (step in 1 until newPaymentsNeeded) {
          cursor = cursor?.let { ManilaTime.advanceDate(it, frequency, customIntervalDays) }
          if (cursor == null) break
        }
        val acceleratedPayoffDate = cursor
        if (acceleratedPayoffDate != null) {
          val cyclesSaved = remainingInstallmentsCount - newPaymentsNeeded
          val formattedDate = ManilaTime.formatIso(acceleratedPayoffDate)
          scenarios.add(
            HypotheticalPayoffScenario(
              scenarioTitle = "Hypothetical: +${MoneyFormatter.formatPesoCompact(extraPerCycleCents)} extra per payment",
              isHypothetical = true,
              projectedPayoffDateIso = formattedDate,
              estimatedTotalPaidCents = remainingBalanceCents,
              estimatedInterestSavedCents = null,
              explanation = "Paying ${MoneyFormatter.formatPeso(acceleratedCyclePayment)} instead of ${MoneyFormatter.formatPeso(installmentAmountCents)} could finish $cyclesSaved payment(s) earlier (around ${ManilaTime.formatDisplay(formattedDate)}), assuming extra payments reduce principal without prepayment fees."
            )
          )
        }
      }
    }

    // 3. Delayed payment warning (only if extracted late fee rule or daily interest rate exists)
    if (!contractualTerms.latePaymentFeeRule.isNullOrBlank() || contractualTerms.dailyInterestRatePercent != null) {
      val graceNote = contractualTerms.gracePeriodDays?.let { " after $it-day grace period" } ?: ""
      val feeDetail = contractualTerms.latePaymentFeeRule
        ?: "Daily interest of ${contractualTerms.dailyInterestRatePercent}% accrues per day late"
      scenarios.add(
        HypotheticalPayoffScenario(
          scenarioTitle = "Hypothetical: Late payment impact",
          isHypothetical = true,
          projectedPayoffDateIso = null,
          estimatedTotalPaidCents = null,
          estimatedInterestSavedCents = null,
          explanation = "Missing a due date$graceNote triggers contractual charges: $feeDetail."
        )
      )
    }

    return scenarios
  }

  /**
   * Deterministic contractual amortization generator for flat-rate, reducing-balance,
   * equal-principal, and daily-interest loans with exact centavo rounding on the final installment.
   */
  fun generateContractualAmortizationTable(
    principalCents: Long,
    installmentCount: Int,
    startDate: LocalDate,
    frequency: RepaymentFrequency,
    method: InterestCalculationMethod,
    annualRatePercent: Double? = null,
    monthlyRatePercent: Double? = null,
    dailyRatePercent: Double? = null,
    totalServiceFeeCents: Long = 0L,
    customIntervalDays: Int? = null
  ): List<ReconstructedScheduleEntry> {
    if (principalCents <= 0L || installmentCount <= 0) return emptyList()

    val periodicRate: Double = when {
      monthlyRatePercent != null -> when (frequency) {
        RepaymentFrequency.MONTHLY -> monthlyRatePercent / 100.0
        RepaymentFrequency.TWICE_MONTHLY -> (monthlyRatePercent / 2.0) / 100.0
        RepaymentFrequency.EVERY_14_DAYS -> (monthlyRatePercent * 14.0 / 30.0) / 100.0
        RepaymentFrequency.WEEKLY -> (monthlyRatePercent * 7.0 / 30.0) / 100.0
        RepaymentFrequency.DAILY -> (monthlyRatePercent / 30.0) / 100.0
        RepaymentFrequency.QUARTERLY -> (monthlyRatePercent * 3.0) / 100.0
        else -> monthlyRatePercent / 100.0
      }
      annualRatePercent != null -> when (frequency) {
        RepaymentFrequency.MONTHLY -> (annualRatePercent / 12.0) / 100.0
        RepaymentFrequency.TWICE_MONTHLY -> (annualRatePercent / 24.0) / 100.0
        RepaymentFrequency.EVERY_14_DAYS -> (annualRatePercent * 14.0 / 365.0) / 100.0
        RepaymentFrequency.WEEKLY -> (annualRatePercent * 7.0 / 365.0) / 100.0
        RepaymentFrequency.DAILY -> (annualRatePercent / 365.0) / 100.0
        RepaymentFrequency.QUARTERLY -> (annualRatePercent / 4.0) / 100.0
        else -> (annualRatePercent / 12.0) / 100.0
      }
      dailyRatePercent != null -> {
        val daysPerPeriod = when (frequency) {
          RepaymentFrequency.DAILY -> 1.0
          RepaymentFrequency.WEEKLY -> 7.0
          RepaymentFrequency.EVERY_14_DAYS -> 14.0
          RepaymentFrequency.TWICE_MONTHLY -> 15.0
          RepaymentFrequency.MONTHLY -> 30.0
          RepaymentFrequency.QUARTERLY -> 90.0
          RepaymentFrequency.CUSTOM_DAYS -> (customIntervalDays ?: 30).toDouble()
          else -> 30.0
        }
        (dailyRatePercent * daysPerPeriod) / 100.0
      }
      else -> 0.0
    }

    val entries = mutableListOf<ReconstructedScheduleEntry>()
    var dueDate = startDate
    var remainingPrincipal = principalCents
    fun stepDate(current: LocalDate): LocalDate =
      if (frequency == RepaymentFrequency.TWICE_MONTHLY) {
        current.plusDays(15)
      } else {
        ManilaTime.advanceDate(current, frequency, customIntervalDays) ?: current.plusMonths(1)
      }

    when (method) {
      InterestCalculationMethod.FLAT_ADD_ON,
      InterestCalculationMethod.FLAT_RATE,
      InterestCalculationMethod.LUMP_SUM_FEE_BASED,
      InterestCalculationMethod.ZERO_INTEREST,
      InterestCalculationMethod.UNKNOWN -> {
        val effectiveRate = if (method == InterestCalculationMethod.ZERO_INTEREST) 0.0 else periodicRate
        val totalInterestCents = BigDecimal(principalCents)
          .multiply(BigDecimal.valueOf(effectiveRate))
          .multiply(BigDecimal(installmentCount))
          .setScale(0, RoundingMode.HALF_UP)
          .toLong()
        val totalContractCents = principalCents + totalInterestCents + totalServiceFeeCents
        val baseInstallment = totalContractCents / installmentCount
        val basePrincipal = principalCents / installmentCount
        val baseInterest = totalInterestCents / installmentCount
        val baseFee = totalServiceFeeCents / installmentCount

        var allocatedPrincipal = 0L
        var allocatedInterest = 0L
        var allocatedFee = 0L

        for (i in 1..installmentCount) {
          val isLast = i == installmentCount
          val pPart = if (isLast) principalCents - allocatedPrincipal else basePrincipal
          val iPart = if (isLast) totalInterestCents - allocatedInterest else baseInterest
          val fPart = if (isLast) totalServiceFeeCents - allocatedFee else baseFee
          val totalPart = if (isLast) {
            totalContractCents - (baseInstallment * (installmentCount - 1))
          } else {
            baseInstallment
          }
          allocatedPrincipal += pPart
          allocatedInterest += iPart
          allocatedFee += fPart
          remainingPrincipal = (remainingPrincipal - pPart).coerceAtLeast(0L)

          entries.add(
            ReconstructedScheduleEntry(
              installmentNumber = i,
              dueDateIso = ManilaTime.formatIso(dueDate),
              principalComponentCents = pPart,
              interestComponentCents = iPart,
              serviceFeeComponentCents = fPart.takeIf { it > 0L },
              totalInstallmentCents = totalPart,
              remainingAmountCents = totalPart,
              remainingPrincipalAfterCents = remainingPrincipal,
              statusLabel = "Upcoming"
            )
          )
          dueDate = stepDate(dueDate)
        }
      }

      InterestCalculationMethod.REDUCING_BALANCE,
      InterestCalculationMethod.FIXED_AMORTIZED -> {
        val levelPaymentCents: Long = if (periodicRate <= 0.0) {
          principalCents / installmentCount
        } else {
          val factor = (1.0 + periodicRate).pow(installmentCount)
          val pmt = principalCents.toDouble() * (periodicRate * factor) / (factor - 1.0)
          BigDecimal.valueOf(pmt).setScale(0, RoundingMode.HALF_UP).toLong()
        }
        val baseFee = totalServiceFeeCents / installmentCount
        var allocatedFee = 0L

        for (i in 1..installmentCount) {
          val isLast = i == installmentCount
          val interestPart = BigDecimal(remainingPrincipal)
            .multiply(BigDecimal.valueOf(periodicRate))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
          val feePart = if (isLast) totalServiceFeeCents - allocatedFee else baseFee
          allocatedFee += feePart

          val principalPart = if (isLast) {
            remainingPrincipal
          } else {
            (levelPaymentCents - interestPart).coerceIn(0L, remainingPrincipal)
          }
          val totalPart = principalPart + interestPart + feePart
          remainingPrincipal = (remainingPrincipal - principalPart).coerceAtLeast(0L)

          entries.add(
            ReconstructedScheduleEntry(
              installmentNumber = i,
              dueDateIso = ManilaTime.formatIso(dueDate),
              principalComponentCents = principalPart,
              interestComponentCents = interestPart,
              serviceFeeComponentCents = feePart.takeIf { it > 0L },
              totalInstallmentCents = totalPart,
              remainingAmountCents = totalPart,
              remainingPrincipalAfterCents = remainingPrincipal,
              statusLabel = "Upcoming"
            )
          )
          dueDate = stepDate(dueDate)
        }
      }

      InterestCalculationMethod.EQUAL_PRINCIPAL,
      InterestCalculationMethod.DAILY_INTEREST,
      InterestCalculationMethod.DAILY_SIMPLE -> {
        val basePrincipal = principalCents / installmentCount
        val baseFee = totalServiceFeeCents / installmentCount
        var allocatedPrincipal = 0L
        var allocatedFee = 0L

        for (i in 1..installmentCount) {
          val isLast = i == installmentCount
          val principalPart = if (isLast) principalCents - allocatedPrincipal else basePrincipal
          val feePart = if (isLast) totalServiceFeeCents - allocatedFee else baseFee
          val interestPart = BigDecimal(remainingPrincipal)
            .multiply(BigDecimal.valueOf(periodicRate))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
          allocatedPrincipal += principalPart
          allocatedFee += feePart
          val totalPart = principalPart + interestPart + feePart
          remainingPrincipal = (remainingPrincipal - principalPart).coerceAtLeast(0L)

          entries.add(
            ReconstructedScheduleEntry(
              installmentNumber = i,
              dueDateIso = ManilaTime.formatIso(dueDate),
              principalComponentCents = principalPart,
              interestComponentCents = interestPart,
              serviceFeeComponentCents = feePart.takeIf { it > 0L },
              totalInstallmentCents = totalPart,
              remainingAmountCents = totalPart,
              remainingPrincipalAfterCents = remainingPrincipal,
              statusLabel = "Upcoming"
            )
          )
          dueDate = stepDate(dueDate)
        }
      }

      InterestCalculationMethod.INTEREST_ONLY_BALLOON -> {
        val baseFee = totalServiceFeeCents / installmentCount
        var allocatedFee = 0L
        for (i in 1..installmentCount) {
          val isLast = i == installmentCount
          val interestPart = BigDecimal(remainingPrincipal)
            .multiply(BigDecimal.valueOf(periodicRate))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
          val principalPart = if (isLast) remainingPrincipal else 0L
          val feePart = if (isLast) totalServiceFeeCents - allocatedFee else baseFee
          allocatedFee += feePart
          val totalPart = principalPart + interestPart + feePart
          remainingPrincipal = (remainingPrincipal - principalPart).coerceAtLeast(0L)

          entries.add(
            ReconstructedScheduleEntry(
              installmentNumber = i,
              dueDateIso = ManilaTime.formatIso(dueDate),
              principalComponentCents = principalPart,
              interestComponentCents = interestPart,
              serviceFeeComponentCents = feePart.takeIf { it > 0L },
              totalInstallmentCents = totalPart,
              remainingAmountCents = totalPart,
              remainingPrincipalAfterCents = remainingPrincipal,
              statusLabel = "Upcoming"
            )
          )
          dueDate = stepDate(dueDate)
        }
      }
    }

    return entries
  }
}
