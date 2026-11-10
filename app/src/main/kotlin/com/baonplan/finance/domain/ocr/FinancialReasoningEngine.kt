package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Pure Kotlin financial reasoning and confidence evaluation engine.
 * Combines extracted values via exact mathematical relationships, detects inconsistencies
 * without silent overwrites, infers missing fields with explicit classification
 * (EXTRACTED / CALCULATED / ESTIMATED), and attaches plain-language explanations.
 */
object FinancialReasoningEngine {

  fun enrichSinglePage(
    raw: SingleScreenshotExtraction,
    pageQuality: OcrPageQuality? = null
  ): SingleScreenshotExtraction {
    val idx = raw.screenshotIndex
    val issues = raw.issues.toMutableList()
    val rows = raw.installmentRows
    val paidRows = rows.filter { it.status == ExtractedInstallmentStatus.PAID }
    val unpaidRows = rows.filter {
      it.status == ExtractedInstallmentStatus.UPCOMING ||
        it.status == ExtractedInstallmentStatus.OVERDUE ||
        it.status == ExtractedInstallmentStatus.UNKNOWN
    }

    // 1. Solve installment counts (Original, Paid, Remaining) without overwriting extracted values
    val (solvedOrig, solvedPaid, solvedRem) = solveInstallmentCounts(
      explicitOrig = raw.originalInstallmentCount,
      explicitPaid = raw.paidInstallmentsCount,
      explicitRem = raw.remainingInstallmentsCount,
      paidRowsCount = paidRows.size,
      unpaidRowsCount = unpaidRows.size,
      screenshotIndex = idx
    )

    // 2. Frequency resolution (prefer explicit label; else analyze >= 3 dates conservatively)
    val (solvedFreq, solvedCustomDays) = if (raw.frequency.value != null &&
      raw.frequency.value != RepaymentFrequency.UNKNOWN
    ) {
      ensureExplanation(raw.frequency) {
        "Explicitly stated in screenshot #${idx + 1}."
      } to ensureExplanation(raw.customIntervalDays) {
        "Interval days read from screenshot #${idx + 1}."
      }
    } else {
      analyzeScheduleFrequency(rows, solvedOrig.value, idx)
    }

    // 3. Individual installment amount fallback (only if uniform across >= 2 rows)
    val solvedInstAmount = if (raw.installmentAmountCents.value != null) {
      ensureExplanation(raw.installmentAmountCents) {
        "Directly read from labeled payment line in screenshot #${idx + 1}."
      }
    } else {
      deriveInstallmentAmountFromRows(rows, raw.totalRepaymentCents.value, solvedOrig.value, idx)
    }

    // 4. Remaining installment total (CALCULATED when not directly extracted)
    val solvedRemTotal = solveRemainingInstallmentTotal(
      extracted = raw.remainingInstallmentTotalCents,
      installmentAmount = solvedInstAmount,
      remainingCount = solvedRem,
      unpaidRows = unpaidRows,
      screenshotIndex = idx
    )

    // 5. Total repayment (CALCULATED or ESTIMATED when not directly extracted)
    val solvedTotalRepayment = solveTotalRepayment(
      extracted = raw.totalRepaymentCents,
      originalPrincipal = raw.originalPrincipalCents,
      interest = raw.interestCents,
      serviceFee = raw.serviceFeeCents,
      processingFee = raw.processingFeeCents,
      otherFees = raw.otherFeesCents,
      installmentAmount = solvedInstAmount,
      originalCount = solvedOrig,
      rows = rows,
      screenshotIndex = idx
    )

    // 6. Honest Loan Status & zero-balance guard
    val (solvedStatus, updatedBalance) = solveLoanStatusAndZeroGuard(
      extractedStatus = raw.loanStatus,
      outstandingBalance = raw.outstandingBalanceCents,
      amountCurrentlyDue = raw.amountCurrentlyDueCents,
      frequency = solvedFreq.value,
      remainingCount = solvedRem.value,
      paidRows = paidRows,
      unpaidRows = unpaidRows,
      totalRowsCount = rows.size,
      screenshotIndex = idx,
      issues = issues
    )

    // 7. Next / Final / Last Paid Dates + missing nextDueDate estimation
    var solvedNextDue = raw.nextDueDateIso
    var isNextExplicit = raw.isNextDueDateExplicit
    if (solvedNextDue.value == null) {
      val firstUnpaidDated = unpaidRows.firstOrNull { it.dueDateIso != null }
      if (firstUnpaidDated != null) {
        isNextExplicit = true
        solvedNextDue = ExtractedField.extracted(
          value = firstUnpaidDated.dueDateIso!!,
          sourceSnippet = firstUnpaidDated.sourceSnippet,
          screenshotIndex = idx,
          confidence = firstUnpaidDated.dateConfidence,
          verificationReason = firstUnpaidDated.verificationNote
            ?: "First upcoming/overdue payment date found in extracted schedule."
        )
      }
    } else {
      solvedNextDue = ensureExplanation(solvedNextDue) {
        "Directly read from next due date label in screenshot #${idx + 1}."
      }
    }

    val lastPaid = ensureExplanation(raw.lastPaidDateIso) {
      "Read from last payment information in screenshot #${idx + 1}."
    }

    if (solvedNextDue.value == null &&
      lastPaid.value != null &&
      solvedStatus.value != DebtStatus.PAID &&
      (solvedRem.value == null || solvedRem.value > 0)
    ) {
      estimateNextDueDateFromHistory(
        lastPaidDate = lastPaid,
        frequency = solvedFreq,
        customIntervalDays = solvedCustomDays.value,
        screenshotIndex = idx
      )?.let { estimatedDate ->
        solvedNextDue = estimatedDate
        isNextExplicit = false
      }
    }

    var solvedFinalDue = ensureExplanation(raw.finalDueDateIso) {
      "Final scheduled payment date found in screenshot #${idx + 1}."
    }
    if (solvedFinalDue.value == null && rows.size >= 2) {
      val lastDated = rows.lastOrNull { it.dueDateIso != null }
      if (lastDated != null && lastDated.dueDateIso != solvedNextDue.value) {
        solvedFinalDue = ExtractedField.extracted(
          value = lastDated.dueDateIso!!,
          sourceSnippet = lastDated.sourceSnippet,
          screenshotIndex = idx,
          confidence = lastDated.dateConfidence,
          verificationReason = lastDated.verificationNote ?: "Final scheduled payment date from schedule in screenshot #${idx + 1}."
        )
      }
    }

    // 8. Balance vs Remaining Installment Total check (preserve both separately!)
    val balVal = updatedBalance.value
    val remTotalVal = solvedRemTotal.value
    if (balVal != null && remTotalVal != null && abs(balVal - remTotalVal) > 100L &&
      raw.remainingInstallmentTotalCents.origin == FieldOrigin.EXTRACTED
    ) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "remainingBalanceCents",
          severity = ImportSeverity.WARNING,
          title = "Balance differs from Remaining Installment Total",
          message = "Screenshot #${idx + 1} shows Balance (${MoneyFormatter.formatPeso(balVal)}) and Remaining Installment Total (${MoneyFormatter.formatPeso(remTotalVal)}) separately — please confirm which reflects your true remaining obligation."
        )
      )
    }

    // 9. Apply OCR page quality downgrades + default explanations across remaining fields
    return raw.copy(
      lender = applyQualityAndDefaultReason(raw.lender, pageQuality) {
        "Matched lender branding in screenshot #${idx + 1}."
      },
      loanName = applyQualityAndDefaultReason(raw.loanName, pageQuality) {
        "Extracted loan title from screenshot #${idx + 1}."
      },
      loanType = applyQualityAndDefaultReason(raw.loanType, pageQuality) {
        "Identified loan product type in screenshot #${idx + 1}."
      },
      loanReferenceId = applyQualityAndDefaultReason(raw.loanReferenceId, pageQuality) {
        "Matched loan or order reference code in screenshot #${idx + 1}."
      },
      originalPrincipalCents = applyQualityAndDefaultReason(raw.originalPrincipalCents, pageQuality) {
        "Extracted original loan principal from screenshot #${idx + 1}."
      },
      amountReceivedCents = applyQualityAndDefaultReason(raw.amountReceivedCents, pageQuality) {
        "Extracted net proceeds disbursed from screenshot #${idx + 1}."
      },
      outstandingBalanceCents = applyQualityAndDefaultReason(updatedBalance, pageQuality) {
        "Extracted current outstanding balance from screenshot #${idx + 1}."
      },
      amountCurrentlyDueCents = applyQualityAndDefaultReason(raw.amountCurrentlyDueCents, pageQuality) {
        "Extracted current billing/payment amount due from screenshot #${idx + 1}."
      },
      remainingInstallmentTotalCents = applyQualityAndDefaultReason(solvedRemTotal, pageQuality) {
        "Extracted remaining installment total from screenshot #${idx + 1}."
      },
      installmentAmountCents = applyQualityAndDefaultReason(solvedInstAmount, pageQuality) {
        "Extracted per-installment payment amount from screenshot #${idx + 1}."
      },
      originalInstallmentCount = applyQualityAndDefaultReason(solvedOrig, pageQuality) {
        "Extracted total installment count from screenshot #${idx + 1}."
      },
      paidInstallmentsCount = applyQualityAndDefaultReason(solvedPaid, pageQuality) {
        "Extracted completed installment count from screenshot #${idx + 1}."
      },
      remainingInstallmentsCount = applyQualityAndDefaultReason(solvedRem, pageQuality) {
        "Extracted remaining installment count from screenshot #${idx + 1}."
      },
      frequency = solvedFreq,
      customIntervalDays = solvedCustomDays,
      nextDueDateIso = if (isNextExplicit) {
        applyQualityAndDefaultReason(solvedNextDue, pageQuality) {
          "Next due date found in screenshot #${idx + 1}."
        }
      } else {
        solvedNextDue
      },
      isNextDueDateExplicit = isNextExplicit,
      finalDueDateIso = applyQualityAndDefaultReason(solvedFinalDue, pageQuality) {
        "Final due date found in screenshot #${idx + 1}."
      },
      lastPaidDateIso = applyQualityAndDefaultReason(lastPaid, pageQuality) {
        "Last payment date found in screenshot #${idx + 1}."
      },
      totalRepaymentCents = applyQualityAndDefaultReason(solvedTotalRepayment, pageQuality) {
        "Total repayment amount found in screenshot #${idx + 1}."
      },
      interestCents = applyQualityAndDefaultReason(raw.interestCents, pageQuality) {
        "Interest amount found in screenshot #${idx + 1}."
      },
      serviceFeeCents = applyQualityAndDefaultReason(raw.serviceFeeCents, pageQuality) {
        "Service fee found in screenshot #${idx + 1}."
      },
      processingFeeCents = applyQualityAndDefaultReason(raw.processingFeeCents, pageQuality) {
        "Processing fee found in screenshot #${idx + 1}."
      },
      otherFeesCents = applyQualityAndDefaultReason(raw.otherFeesCents, pageQuality) {
        "Additional fee(s) found in screenshot #${idx + 1}."
      },
      loanStatus = solvedStatus,
      issues = issues.distinctBy { Triple(it.fieldKey, it.severity, it.title) }
    )
  }

  fun enrichMergedDraft(
    draft: DebtImportDraft,
    hadExplicitFrequencyAcrossPages: Boolean
  ): DebtImportDraft {
    val rows = draft.installmentRows
    val paidRows = rows.filter { it.status == ExtractedInstallmentStatus.PAID }
    val unpaidRows = rows.filter {
      it.status == ExtractedInstallmentStatus.UPCOMING ||
        it.status == ExtractedInstallmentStatus.OVERDUE ||
        it.status == ExtractedInstallmentStatus.UNKNOWN
    }

    val (solvedOrig, solvedPaid, solvedRem) = solveInstallmentCounts(
      explicitOrig = draft.originalInstallmentCount,
      explicitPaid = draft.paidInstallmentsCount,
      explicitRem = draft.remainingInstallmentsCount,
      paidRowsCount = paidRows.size,
      unpaidRowsCount = unpaidRows.size,
      screenshotIndex = draft.sourceScreenshotIndices.firstOrNull()
    )

    val (solvedFreq, solvedCustomDays) = if (hadExplicitFrequencyAcrossPages &&
      draft.frequency.value != null &&
      draft.frequency.value != RepaymentFrequency.UNKNOWN
    ) {
      ensureExplanation(draft.frequency) { "Explicitly stated across scanned screenshots." } to
        ensureExplanation(draft.customIntervalDays) { "Custom interval days stated in screenshots." }
    } else {
      analyzeScheduleFrequency(rows, solvedOrig.value, draft.sourceScreenshotIndices.firstOrNull())
    }

    val solvedInst = if (draft.installmentAmountCents.value != null) {
      ensureExplanation(draft.installmentAmountCents) { "Extracted per-payment amount." }
    } else {
      deriveInstallmentAmountFromRows(
        rows = rows,
        totalRepayment = draft.totalRepaymentCents.value,
        origCount = solvedOrig.value,
        screenshotIndex = draft.sourceScreenshotIndices.firstOrNull()
      )
    }

    val solvedRemTotal = solveRemainingInstallmentTotal(
      extracted = draft.remainingInstallmentTotalCents,
      installmentAmount = solvedInst,
      remainingCount = solvedRem,
      unpaidRows = unpaidRows,
      screenshotIndex = draft.sourceScreenshotIndices.firstOrNull()
    )

    val solvedTotalRepayment = solveTotalRepayment(
      extracted = draft.totalRepaymentCents,
      originalPrincipal = draft.originalPrincipalCents,
      interest = draft.interestCents,
      serviceFee = draft.serviceFeeCents,
      processingFee = draft.processingFeeCents,
      otherFees = draft.otherFeesCents,
      installmentAmount = solvedInst,
      originalCount = solvedOrig,
      rows = rows,
      screenshotIndex = draft.sourceScreenshotIndices.firstOrNull()
    )

    val issues = draft.validationIssues.toMutableList()
    val (solvedStatus, updatedBalance) = solveLoanStatusAndZeroGuard(
      extractedStatus = draft.loanStatus,
      outstandingBalance = draft.outstandingBalanceCents,
      amountCurrentlyDue = draft.amountCurrentlyDueCents,
      frequency = solvedFreq.value,
      remainingCount = solvedRem.value,
      paidRows = paidRows,
      unpaidRows = unpaidRows,
      totalRowsCount = rows.size,
      screenshotIndex = draft.sourceScreenshotIndices.firstOrNull(),
      issues = issues
    )

    var solvedNextDue = ensureExplanation(draft.nextDueDateIso) {
      "Extracted next payment date."
    }
    var updatedDueStatus = draft.dueDateStatus
    if (solvedNextDue.value == null) {
      val firstUnpaidDated = unpaidRows.firstOrNull { it.dueDateIso != null }
      if (firstUnpaidDated != null) {
        solvedNextDue = ExtractedField.extracted(
          value = firstUnpaidDated.dueDateIso!!,
          sourceSnippet = firstUnpaidDated.sourceSnippet,
          screenshotIndex = firstUnpaidDated.screenshotIndex,
          confidence = firstUnpaidDated.dateConfidence,
          verificationReason = firstUnpaidDated.verificationNote
            ?: "First upcoming/overdue payment date from merged schedule."
        )
        updatedDueStatus = if (firstUnpaidDated.dateConfidence == ExtractionConfidence.HIGH) {
          DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
        } else {
          DueDateStatus.UNCONFIRMED
        }
      } else if (draft.lastPaidDateIso.value != null &&
        solvedStatus.value != DebtStatus.PAID &&
        (solvedRem.value == null || solvedRem.value > 0)
      ) {
        estimateNextDueDateFromHistory(
          lastPaidDate = draft.lastPaidDateIso,
          frequency = solvedFreq,
          customIntervalDays = solvedCustomDays.value,
          screenshotIndex = draft.sourceScreenshotIndices.firstOrNull()
        )?.let { estimated ->
          solvedNextDue = estimated
          updatedDueStatus = DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
        }
      }
    }

    // Internal consistency boost / warning across balance, counts, and schedule
    val mergedFreqConsistent = solvedFreq.value != null &&
      solvedFreq.value != RepaymentFrequency.UNKNOWN &&
      solvedNextDue.value != null &&
      rows.size >= 3 &&
      !solvedNextDue.hasConflict

    val finalFreq = if (solvedFreq.origin == FieldOrigin.EXTRACTED && mergedFreqConsistent &&
      solvedFreq.confidence == ExtractionConfidence.MEDIUM
    ) {
      solvedFreq.copy(
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "${solvedFreq.verificationReason ?: "Explicit frequency"} Verified by ${rows.size} extracted schedule dates."
      )
    } else {
      solvedFreq
    }

    return draft.copy(
      outstandingBalanceCents = updatedBalance,
      remainingInstallmentTotalCents = solvedRemTotal,
      installmentAmountCents = solvedInst,
      originalInstallmentCount = solvedOrig,
      paidInstallmentsCount = solvedPaid,
      remainingInstallmentsCount = solvedRem,
      frequency = finalFreq,
      customIntervalDays = solvedCustomDays,
      nextDueDateIso = solvedNextDue,
      totalRepaymentCents = solvedTotalRepayment,
      loanStatus = solvedStatus,
      dueDateStatus = if (solvedNextDue.value == null) DueDateStatus.UNKNOWN else updatedDueStatus,
      validationIssues = issues.distinctBy { Triple(it.fieldKey, it.severity, it.title) }
    )
  }

  internal fun analyzeScheduleFrequency(
    rows: List<ExtractedInstallmentRow>,
    explicitOriginalCount: Int?,
    screenshotIndex: Int?
  ): Pair<ExtractedField<RepaymentFrequency>, ExtractedField<Int>> {
    val datedRows = rows.mapNotNull { r -> ManilaTime.parseDate(r.dueDateIso)?.let { r to it } }
      .distinctBy { it.second }
      .sortedBy { it.second }

    if (datedRows.size == 1 && explicitOriginalCount == 1) {
      val row = datedRows.first().first
      return ExtractedField.estimated(
        value = RepaymentFrequency.ONE_TIME,
        sourceSnippet = row.sourceSnippet,
        screenshotIndex = screenshotIndex ?: row.screenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "One installment total detected — confirm single-payment schedule."
      ) to ExtractedField.notDetected()
    }

    // Never guess Weekly / Biweekly / Monthly from fewer than 3 dated rows!
    if (datedRows.size < 3) {
      return ExtractedField.notDetected<RepaymentFrequency>(
        reason = if (datedRows.isEmpty()) {
          "No payment schedule dates found."
        } else {
          "Only ${datedRows.size} repayment date(s) detected; at least 3 are needed to confirm a recurring pattern."
        }
      ) to ExtractedField.notDetected()
    }

    val dates = datedRows.map { it.second }
    val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
    val snippet = datedRows.take(3).joinToString(" -> ") {
      ManilaTime.formatShort(ManilaTime.formatIso(it.second))
    }
    val srcIdx = screenshotIndex ?: datedRows.first().first.screenshotIndex
    val allRowDatesHighConf = datedRows.all { it.first.dateConfidence == ExtractionConfidence.HIGH }

    // 1. Weekly (exact 7d, or 6–8d holiday/weekend shift with bounded cumulative anchor drift)
    if (gaps.all { it == 7 }) {
      return ExtractedField.estimated(
        value = RepaymentFrequency.WEEKLY,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = if (allRowDatesHighConf) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates were detected at consistent 7-day intervals."
      ) to ExtractedField.notDetected()
    }
    if (gaps.all { it in 6..8 } && maxAnchorDriftDays(dates, 7L) <= 2L) {
      val minG = gaps.minOrNull() ?: 7
      val maxG = gaps.maxOrNull() ?: 7
      return ExtractedField.estimated(
        value = RepaymentFrequency.WEEKLY,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates were detected with intervals of approximately $minG to $maxG days."
      ) to ExtractedField.notDetected()
    }

    // 2. Biweekly / Every 14 days (exact 14d, or 13–15d jitter without semi-monthly 15th/30th alternation)
    if (gaps.all { it == 14 }) {
      return ExtractedField.estimated(
        value = RepaymentFrequency.EVERY_14_DAYS,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = if (allRowDatesHighConf) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates were detected at consistent 14-day intervals."
      ) to ExtractedField.notDetected()
    }
    val semiMonthlyHits = dates.count { it.dayOfMonth in 14..16 || it.dayOfMonth >= 28 }
    val looksSemiMonthly = semiMonthlyHits >= maxOf(3, dates.size - 1) &&
      dates.any { it.dayOfMonth in 14..16 } &&
      dates.any { it.dayOfMonth >= 28 }
    if (!looksSemiMonthly && gaps.all { it in 13..15 } && maxAnchorDriftDays(dates, 14L) <= 2L) {
      val minG = gaps.minOrNull() ?: 14
      val maxG = gaps.maxOrNull() ?: 14
      return ExtractedField.estimated(
        value = RepaymentFrequency.EVERY_14_DAYS,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates were detected with intervals of approximately $minG to $maxG days."
      ) to ExtractedField.notDetected()
    }

    // 3. Monthly (exact calendar day or end-of-month; or ±2-day holiday/weekend shift across consecutive months)
    if (isStrictMonthlyDates(dates)) {
      return ExtractedField.estimated(
        value = RepaymentFrequency.MONTHLY,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = if (allRowDatesHighConf) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates follow a consistent monthly cycle."
      ) to ExtractedField.notDetected()
    }
    val isShiftedMonthly = dates.zipWithNext().all { (a, b) ->
      val monthDiff = (b.year * 12 + b.monthValue) - (a.year * 12 + a.monthValue)
      monthDiff == 1 && abs(ChronoUnit.DAYS.between(a.plusMonths(1), b)) <= 2L
    } && abs(ChronoUnit.DAYS.between(dates.first().plusMonths((dates.size - 1).toLong()), dates.last())) <= 2L
    if (isShiftedMonthly) {
      return ExtractedField.estimated(
        value = RepaymentFrequency.MONTHLY,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates occur roughly 1 month apart (with 1–2 day weekend/holiday adjustments)."
      ) to ExtractedField.notDetected()
    }

    // 4. Exact Custom N days (never force semi-monthly 15th/30th cycles into Custom 15 days)
    val uniqueGaps = gaps.distinct()
    if (!looksSemiMonthly && uniqueGaps.size == 1 && uniqueGaps.single() in 1..180) {
      val n = uniqueGaps.single()
      return ExtractedField.estimated(
        value = RepaymentFrequency.CUSTOM_DAYS,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "${dates.size} repayment dates occur consistently every $n days."
      ) to ExtractedField.estimated(
        value = n,
        sourceSnippet = snippet,
        screenshotIndex = srcIdx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Derived from exact $n-day gap between detected payment dates."
      )
    }

    // 5. Semi-monthly or irregular dates preserved faithfully
    val irregularReason = if (looksSemiMonthly) {
      "Dates follow a semi-monthly calendar pattern (e.g. 15th & end of month), so custom dates are preserved."
    } else {
      "Intervals vary (${gaps.joinToString("d, ")}d), so custom dates are kept instead of forcing a fixed cycle."
    }
    return ExtractedField.extracted(
      value = RepaymentFrequency.IRREGULAR,
      sourceSnippet = snippet,
      screenshotIndex = srcIdx,
      confidence = ExtractionConfidence.HIGH,
      verificationReason = irregularReason
    ) to ExtractedField.notDetected()
  }

  private fun maxAnchorDriftDays(dates: List<LocalDate>, periodDays: Long): Long {
    val start = dates.first()
    var maxDrift = 0L
    for (idx in dates.indices) {
      val expected = start.plusDays(periodDays * idx.toLong())
      val drift = abs(ChronoUnit.DAYS.between(expected, dates[idx]))
      if (drift > maxDrift) maxDrift = drift
    }
    return maxDrift
  }

  private fun isStrictMonthlyDates(dates: List<LocalDate>): Boolean =
    dates.zipWithNext().all { (a, b) ->
      a.plusMonths(1) == b ||
        ((b.year * 12 + b.monthValue) - (a.year * 12 + a.monthValue) == 1 &&
          (a.dayOfMonth == b.dayOfMonth || (a.dayOfMonth == a.lengthOfMonth() && b.dayOfMonth == b.lengthOfMonth())))
    }

  private fun solveInstallmentCounts(
    explicitOrig: ExtractedField<Int>,
    explicitPaid: ExtractedField<Int>,
    explicitRem: ExtractedField<Int>,
    paidRowsCount: Int,
    unpaidRowsCount: Int,
    screenshotIndex: Int?
  ): Triple<ExtractedField<Int>, ExtractedField<Int>, ExtractedField<Int>> {
    var orig = ensureExplanation(explicitOrig) { "Extracted total installments count." }
    var paid = ensureExplanation(explicitPaid) { "Extracted paid installments count." }
    var rem = ensureExplanation(explicitRem) { "Extracted remaining installments count." }

    // First bring in schedule-row counts ONLY where missing
    if (paid.value == null && paidRowsCount > 0) {
      val consistentWithOrig = orig.value == null || paidRowsCount <= orig.value
      if (consistentWithOrig) {
        paid = ExtractedField.calculated(
          value = paidRowsCount,
          sourceSnippet = "$paidRowsCount paid schedule row(s)",
          screenshotIndex = screenshotIndex,
          confidence = ExtractionConfidence.MEDIUM,
          verificationReason = "Counted $paidRowsCount paid payment row(s) in the visible schedule — confirm earlier payments aren't scrolled off-screen."
        )
      }
    }

    if (rem.value == null && unpaidRowsCount > 0) {
      val consistentWithOrig = orig.value == null || unpaidRowsCount <= orig.value
      if (consistentWithOrig) {
        rem = ExtractedField.calculated(
          value = unpaidRowsCount,
          sourceSnippet = "$unpaidRowsCount upcoming/overdue row(s)",
          screenshotIndex = screenshotIndex,
          confidence = ExtractionConfidence.MEDIUM,
          verificationReason = "Counted $unpaidRowsCount upcoming/overdue row(s) in the visible schedule."
        )
      }
    }

    // Exact arithmetic completion: Orig - Paid = Rem, etc.
    val oVal = orig.value
    val pVal = paid.value
    val rVal = rem.value

    if (rem.value == null && oVal != null && pVal != null && oVal >= pVal) {
      val diff = oVal - pVal
      val bothHigh = orig.confidence == ExtractionConfidence.HIGH && paid.confidence == ExtractionConfidence.HIGH
      rem = ExtractedField.calculated(
        value = diff,
        sourceSnippet = "$oVal - $pVal",
        screenshotIndex = screenshotIndex ?: orig.sourceScreenshotIndex ?: paid.sourceScreenshotIndex,
        confidence = if (bothHigh) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "Calculated as total installments ($oVal) minus paid installments ($pVal)."
      )
    } else if (paid.value == null && oVal != null && rVal != null && oVal >= rVal) {
      val diff = oVal - rVal
      val bothHigh = orig.confidence == ExtractionConfidence.HIGH && rem.confidence == ExtractionConfidence.HIGH
      paid = ExtractedField.calculated(
        value = diff,
        sourceSnippet = "$oVal - $rVal",
        screenshotIndex = screenshotIndex ?: orig.sourceScreenshotIndex ?: rem.sourceScreenshotIndex,
        confidence = if (bothHigh) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "Calculated as total installments ($oVal) minus remaining installments ($rVal)."
      )
    } else if (orig.value == null && pVal != null && rVal != null && (pVal + rVal) > 0) {
      val sum = pVal + rVal
      val corroboratingScheduleRows = paidRowsCount == pVal && unpaidRowsCount == rVal
      orig = ExtractedField.calculated(
        value = sum,
        sourceSnippet = "$pVal + $rVal",
        screenshotIndex = screenshotIndex ?: paid.sourceScreenshotIndex ?: rem.sourceScreenshotIndex,
        confidence = if (corroboratingScheduleRows ||
          (paid.confidence == ExtractionConfidence.HIGH && rem.confidence == ExtractionConfidence.HIGH)
        ) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "Calculated as paid installments ($pVal) plus remaining installments ($rVal)."
      )
    }

    return Triple(orig, paid, rem)
  }

  private fun deriveInstallmentAmountFromRows(
    rows: List<ExtractedInstallmentRow>,
    totalRepayment: Long?,
    origCount: Int?,
    screenshotIndex: Int?
  ): ExtractedField<Long> {
    val positiveRows = rows.mapNotNull { r -> r.amountCents?.takeIf { it > 0L }?.let { r to it } }
    val distinctAmounts = positiveRows.map { it.second }.distinct()
    if (distinctAmounts.size == 1 && rows.size >= 2) {
      val amt = distinctAmounts.single()
      val sampleRow = positiveRows.first().first
      return ExtractedField.estimated(
        value = amt,
        sourceSnippet = sampleRow.sourceSnippet,
        screenshotIndex = screenshotIndex ?: sampleRow.screenshotIndex,
        confidence = if (rows.size >= 3) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
        verificationReason = "Consistent ${MoneyFormatter.formatPeso(amt)} payment detected across ${rows.size} schedule rows."
      )
    }
    if (totalRepayment != null && totalRepayment > 0L && origCount == 1) {
      return ExtractedField.estimated(
        value = totalRepayment,
        sourceSnippet = "1 × ${MoneyFormatter.formatPeso(totalRepayment)}",
        screenshotIndex = screenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Estimated from total repayment for a single installment."
      )
    }
    return ExtractedField.notDetected()
  }

  private fun solveRemainingInstallmentTotal(
    extracted: ExtractedField<Long>,
    installmentAmount: ExtractedField<Long>,
    remainingCount: ExtractedField<Int>,
    unpaidRows: List<ExtractedInstallmentRow>,
    screenshotIndex: Int?
  ): ExtractedField<Long> {
    if (extracted.value != null) {
      return ensureExplanation(extracted) {
        "Extracted directly from remaining installment total label."
      }
    }

    val unpaidAmts = unpaidRows.mapNotNull { it.amountCents }
    if (unpaidRows.isNotEmpty() && unpaidAmts.size == unpaidRows.size) {
      val sum = unpaidAmts.sum()
      if (sum > 0L && (remainingCount.value == null || remainingCount.value == unpaidRows.size)) {
        return ExtractedField.calculated(
          value = sum,
          sourceSnippet = "${unpaidRows.size} unpaid schedule rows",
          screenshotIndex = screenshotIndex ?: unpaidRows.first().screenshotIndex,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Calculated by summing all ${unpaidRows.size} extracted upcoming/overdue rows."
        )
      }
    }

    val inst = installmentAmount.value
    val rem = remainingCount.value
    if (inst != null && inst > 0L && rem != null && rem > 0) {
      val product = inst * rem.toLong()
      val hasConflictingRowAmounts = unpaidAmts.distinct().size > 1
      if (!hasConflictingRowAmounts) {
        val equalEvidence = unpaidRows.isEmpty() || unpaidAmts.all { abs(it - inst) <= 50L }
        if (equalEvidence) {
          val bothHigh = installmentAmount.confidence == ExtractionConfidence.HIGH &&
            remainingCount.confidence == ExtractionConfidence.HIGH &&
            unpaidRows.size == rem
          return ExtractedField.calculated(
            value = product,
            sourceSnippet = "$rem × ${MoneyFormatter.formatPeso(inst)}",
            screenshotIndex = screenshotIndex ?: installmentAmount.sourceScreenshotIndex ?: remainingCount.sourceScreenshotIndex,
            confidence = if (bothHigh) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
            verificationReason = "Calculated as $rem remaining installments × ${MoneyFormatter.formatPeso(inst)} per payment."
          )
        }
      }
    }

    return extracted
  }

  private fun solveTotalRepayment(
    extracted: ExtractedField<Long>,
    originalPrincipal: ExtractedField<Long>,
    interest: ExtractedField<Long>,
    serviceFee: ExtractedField<Long>,
    processingFee: ExtractedField<Long>,
    otherFees: ExtractedField<Long>,
    installmentAmount: ExtractedField<Long>,
    originalCount: ExtractedField<Int>,
    rows: List<ExtractedInstallmentRow>,
    screenshotIndex: Int?
  ): ExtractedField<Long> {
    if (extracted.value != null) {
      return ensureExplanation(extracted) { "Extracted total repayment amount." }
    }

    val principal = originalPrincipal.value
    val hasAnyFeeOrInterest = interest.value != null ||
      serviceFee.value != null ||
      processingFee.value != null ||
      otherFees.value != null
    if (principal != null && principal > 0L && hasAnyFeeOrInterest) {
      val total = principal +
        (interest.value ?: 0L) +
        (serviceFee.value ?: 0L) +
        (processingFee.value ?: 0L) +
        (otherFees.value ?: 0L)
      return ExtractedField.calculated(
        value = total,
        sourceSnippet = "Principal + fees",
        screenshotIndex = screenshotIndex ?: originalPrincipal.sourceScreenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Calculated from original loan (${MoneyFormatter.formatPeso(principal)}) plus detected interest and fees."
      )
    }

    val inst = installmentAmount.value
    val orig = originalCount.value
    if (inst != null && inst > 0L && orig != null && orig > 0 && !hasAnyFeeOrInterest) {
      val distinctRowAmts = rows.mapNotNull { it.amountCents }.distinct()
      val uniformRowsOrNone = distinctRowAmts.isEmpty() || (distinctRowAmts.size == 1 && abs(distinctRowAmts.single() - inst) <= 50L)
      if (uniformRowsOrNone &&
        installmentAmount.origin == FieldOrigin.EXTRACTED &&
        originalCount.origin == FieldOrigin.EXTRACTED
      ) {
        val estTotal = inst * orig.toLong()
        return ExtractedField.estimated(
          value = estTotal,
          sourceSnippet = "$orig × ${MoneyFormatter.formatPeso(inst)}",
          screenshotIndex = screenshotIndex ?: installmentAmount.sourceScreenshotIndex,
          confidence = ExtractionConfidence.MEDIUM,
          verificationReason = "Estimated as $orig installments × ${MoneyFormatter.formatPeso(inst)} — confirm whether installments vary or fees apply."
        )
      }
    }

    return extracted
  }

  private fun solveLoanStatusAndZeroGuard(
    extractedStatus: ExtractedField<DebtStatus>,
    outstandingBalance: ExtractedField<Long>,
    amountCurrentlyDue: ExtractedField<Long>,
    frequency: RepaymentFrequency?,
    remainingCount: Int?,
    paidRows: List<ExtractedInstallmentRow>,
    unpaidRows: List<ExtractedInstallmentRow>,
    totalRowsCount: Int,
    screenshotIndex: Int?,
    issues: MutableList<ImportValidationIssue>
  ): Pair<ExtractedField<DebtStatus>, ExtractedField<Long>> {
    var updatedBal = outstandingBalance
    val status = if (extractedStatus.value != null) {
      ensureExplanation(extractedStatus) { "Explicit loan status detected." }
    } else if (totalRowsCount > 0 && unpaidRows.isEmpty() && paidRows.isNotEmpty() &&
      (remainingCount == null || remainingCount == 0) &&
      (outstandingBalance.value == null || outstandingBalance.value == 0L)
    ) {
      ExtractedField.estimated(
        value = DebtStatus.PAID,
        sourceSnippet = paidRows.last().sourceSnippet,
        screenshotIndex = screenshotIndex ?: paidRows.last().screenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "All detected schedule rows are marked Paid — verify loan is settled."
      )
    } else {
      ExtractedField.notDetected()
    }

    // Never assume loan is fully paid just because balance field read as ₱0.00
    if (updatedBal.value == 0L && status.value != DebtStatus.PAID) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "remainingBalanceCents",
          severity = ImportSeverity.WARNING,
          title = "Zero balance detected — confirm loan status",
          message = "Balance read as ₱0.00 without a \"Paid in full\" label. Verify whether installments remain or this cycle simply has ₱0.00 due right now."
        )
      )
      updatedBal = updatedBal.copy(
        confidence = ExtractionConfidence.LOW,
        verificationReason = "Read as ₱0.00 without paid-in-full confirmation — verify remaining obligation."
      )
    }

    // Estimate outstanding balance ONLY when clearly backed (e.g. single remaining cycle matching Amount Currently Due)
    if (updatedBal.value == null && amountCurrentlyDue.value != null && amountCurrentlyDue.value > 0L &&
      (frequency == RepaymentFrequency.ONE_TIME || remainingCount == 1)
    ) {
      updatedBal = ExtractedField.estimated(
        value = amountCurrentlyDue.value,
        sourceSnippet = amountCurrentlyDue.sourceSnippet,
        screenshotIndex = screenshotIndex ?: amountCurrentlyDue.sourceScreenshotIndex,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Only 1 remaining payment is indicated, so remaining balance likely matches amount currently due."
      )
    }

    return status to updatedBal
  }

  private fun estimateNextDueDateFromHistory(
    lastPaidDate: ExtractedField<String>,
    frequency: ExtractedField<RepaymentFrequency>,
    customIntervalDays: Int?,
    screenshotIndex: Int?
  ): ExtractedField<String>? {
    val lastPaid = ManilaTime.parseDate(lastPaidDate.value) ?: return null
    val freq = frequency.value ?: return null
    val nextLocal: LocalDate? = when (freq) {
      RepaymentFrequency.WEEKLY -> lastPaid.plusDays(7)
      RepaymentFrequency.EVERY_14_DAYS -> lastPaid.plusDays(14)
      RepaymentFrequency.MONTHLY -> lastPaid.plusMonths(1)
      RepaymentFrequency.CUSTOM_DAYS -> customIntervalDays?.takeIf { it in 1..365 }?.let { lastPaid.plusDays(it.toLong()) }
      RepaymentFrequency.ONE_TIME, RepaymentFrequency.IRREGULAR, RepaymentFrequency.UNKNOWN -> null
    }
    if (nextLocal == null) return null
    val iso = ManilaTime.formatIso(nextLocal)
    val patternDesc = when (freq) {
      RepaymentFrequency.WEEKLY -> "weekly (+7 days)"
      RepaymentFrequency.EVERY_14_DAYS -> "every 14 days (+14 days)"
      RepaymentFrequency.MONTHLY -> "monthly (+1 month)"
      RepaymentFrequency.CUSTOM_DAYS -> "every $customIntervalDays days"
      else -> freq.label
    }
    return ExtractedField.estimated(
      value = iso,
      sourceSnippet = "Last paid ${ManilaTime.formatShort(lastPaidDate.value!!)}",
      screenshotIndex = screenshotIndex ?: lastPaidDate.sourceScreenshotIndex,
      confidence = ExtractionConfidence.MEDIUM,
      verificationReason = "Estimated from last payment date (${ManilaTime.formatDisplay(lastPaidDate.value)}) using $patternDesc schedule. Not directly confirmed by lender."
    )
  }

  private fun <T> ensureExplanation(
    field: ExtractedField<T>,
    defaultReason: () -> String
  ): ExtractedField<T> =
    if (field.value != null && field.verificationReason.isNullOrBlank()) {
      field.copy(verificationReason = defaultReason())
    } else {
      field
    }

  private fun <T> applyQualityAndDefaultReason(
    field: ExtractedField<T>,
    quality: OcrPageQuality?,
    defaultReason: () -> String
  ): ExtractedField<T> {
    if (field.value == null) return field
    val withReason = ensureExplanation(field, defaultReason)
    if (quality == null || withReason.origin != FieldOrigin.EXTRACTED) return withReason

    val avg = quality.averageConfidence
    val poorOcr = (avg != null && avg < 0.72f) ||
      (quality.usedContrastEnhancement && avg != null && avg < 0.78f)
    return if (poorOcr && withReason.confidence == ExtractionConfidence.HIGH) {
      withReason.copy(
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "${withReason.verificationReason} (Image clarity was low — please double-check)"
      )
    } else {
      withReason
    }
  }
}
