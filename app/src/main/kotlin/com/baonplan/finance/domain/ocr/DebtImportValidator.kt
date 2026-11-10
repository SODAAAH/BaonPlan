package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import kotlin.math.abs

/**
 * Pure Kotlin validation & financial consistency checks for imported/edited debt data.
 * Never silently overwrites extracted or user-edited balances with conflicting figures.
 */
object DebtImportValidator {

  fun validateDraft(draft: DebtImportDraft): List<ImportValidationIssue> =
    validateValues(
      debtName = draft.loanName.value.orEmpty(),
      remainingBalanceCents = draft.outstandingBalanceCents.value
        ?: draft.remainingInstallmentTotalCents.value
        ?: draft.calculatedUnpaidScheduleSumCents.value
        ?: draft.amountCurrentlyDueCents.value,
      explicitBalanceCents = draft.outstandingBalanceCents.value,
      explicitAmountCurrentlyDueCents = draft.amountCurrentlyDueCents.value,
      explicitRemainingInstallmentTotalCents = draft.remainingInstallmentTotalCents.value,
      originalPrincipalCents = draft.originalPrincipalCents.value,
      amountReceivedCents = draft.amountReceivedCents.value,
      interestCents = draft.interestCents.value,
      serviceFeeCents = draft.serviceFeeCents.value,
      processingFeeCents = draft.processingFeeCents.value,
      otherFeesCents = draft.otherFeesCents.value,
      totalScheduledCents = draft.totalRepaymentCents.value,
      installmentAmountCents = draft.installmentAmountCents.value,
      originalInstallmentCount = draft.originalInstallmentCount.value,
      paidInstallmentsCount = draft.paidInstallmentsCount.value,
      remainingInstallmentsCount = draft.remainingInstallmentsCount.value,
      frequency = draft.frequency.value ?: RepaymentFrequency.UNKNOWN,
      customIntervalDays = draft.customIntervalDays.value,
      nextDueDateIso = draft.nextDueDateIso.value,
      dueDateStatus = draft.dueDateStatus,
      scheduleRows = draft.installmentRows,
      requireNonBlankName = false
    )

  fun validateValues(
    debtName: String,
    remainingBalanceCents: Long?,
    explicitBalanceCents: Long? = remainingBalanceCents,
    explicitAmountCurrentlyDueCents: Long? = null,
    explicitRemainingInstallmentTotalCents: Long? = null,
    originalPrincipalCents: Long? = null,
    amountReceivedCents: Long? = null,
    interestCents: Long? = null,
    serviceFeeCents: Long? = null,
    processingFeeCents: Long? = null,
    otherFeesCents: Long? = null,
    totalScheduledCents: Long? = null,
    installmentAmountCents: Long? = null,
    originalInstallmentCount: Int? = null,
    paidInstallmentsCount: Int? = null,
    remainingInstallmentsCount: Int? = null,
    frequency: RepaymentFrequency = RepaymentFrequency.UNKNOWN,
    customIntervalDays: Int? = null,
    nextDueDateIso: String? = null,
    dueDateStatus: DueDateStatus = DueDateStatus.UNCONFIRMED,
    scheduleRows: List<ExtractedInstallmentRow> = emptyList(),
    requireNonBlankName: Boolean = true
  ): List<ImportValidationIssue> {
    val issues = mutableListOf<ImportValidationIssue>()

    // 1. Hard blocking errors
    if (requireNonBlankName && debtName.isBlank()) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "loanName",
          severity = ImportSeverity.ERROR,
          title = "Debt name is required",
          message = "Enter a clear name so you can identify this loan."
        )
      )
    }

    if (remainingBalanceCents == null || remainingBalanceCents < 0L) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "remainingBalanceCents",
          severity = ImportSeverity.ERROR,
          title = "Remaining balance needs a valid peso amount",
          message = "Enter ₱0.00 or a positive peso amount before saving."
        )
      )
    }

    val nonNegativeMap = listOf(
      "originalPrincipalCents" to ("Original principal" to originalPrincipalCents),
      "amountReceivedCents" to ("Amount received" to amountReceivedCents),
      "interestCents" to ("Interest" to interestCents),
      "serviceFeeCents" to ("Service fee" to serviceFeeCents),
      "processingFeeCents" to ("Processing fee" to processingFeeCents),
      "otherFeesCents" to ("Other fees" to otherFeesCents),
      "totalRepaymentCents" to ("Total scheduled repayment" to totalScheduledCents),
      "amountCurrentlyDueCents" to ("Amount currently due" to explicitAmountCurrentlyDueCents),
      "remainingInstallmentTotalCents" to ("Remaining installment total" to explicitRemainingInstallmentTotalCents)
    )
    for ((key, pair) in nonNegativeMap) {
      val value = pair.second
      if (value != null && value < 0L) {
        issues.add(
          ImportValidationIssue(
            fieldKey = key,
            severity = ImportSeverity.ERROR,
            title = "${pair.first} cannot be negative",
            message = "Check ${pair.first.lowercase()} and enter a non-negative peso amount."
          )
        )
      }
    }

    if (installmentAmountCents != null && installmentAmountCents <= 0L) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "installmentAmountCents",
          severity = ImportSeverity.ERROR,
          title = "Installment amount must be positive",
          message = "Leave Per payment blank if unknown, or enter a positive peso amount."
        )
      )
    }

    if (originalInstallmentCount != null && originalInstallmentCount <= 0) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "originalInstallmentCount",
          severity = ImportSeverity.ERROR,
          title = "Original installment count must be at least 1",
          message = "Clear original count if unknown, or enter 1 or more."
        )
      )
    }

    if (paidInstallmentsCount != null && paidInstallmentsCount < 0) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "paidInstallmentsCount",
          severity = ImportSeverity.ERROR,
          title = "Paid installments cannot be negative",
          message = "Enter 0 or higher."
        )
      )
    }

    if (remainingInstallmentsCount != null && remainingInstallmentsCount < 0) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "remainingInstallmentsCount",
          severity = ImportSeverity.ERROR,
          title = "Remaining installments cannot be negative",
          message = "Enter 0 or higher."
        )
      )
    }

    if (originalInstallmentCount != null && paidInstallmentsCount != null && paidInstallmentsCount > originalInstallmentCount) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "paidInstallmentsCount",
          severity = ImportSeverity.ERROR,
          title = "Paid count exceeds original installments",
          message = "Paid installments ($paidInstallmentsCount) cannot be greater than original installments ($originalInstallmentCount)."
        )
      )
    }

    if (frequency == RepaymentFrequency.CUSTOM_DAYS && (customIntervalDays == null || customIntervalDays <= 0)) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "customIntervalDays",
          severity = ImportSeverity.ERROR,
          title = "Custom interval days required",
          message = "Enter a positive day interval (at least 1 day) or change frequency."
        )
      )
    }

    if ((remainingBalanceCents == null || remainingBalanceCents != 0L) &&
      dueDateStatus == DueDateStatus.CONFIRMED &&
      ManilaTime.parseDate(nextDueDateIso) == null
    ) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "nextDueDate",
          severity = ImportSeverity.ERROR,
          title = "Confirmed due date missing",
          message = "Pick a next due date before marking the due date status as Confirmed."
        )
      )
    }

    // Check individual schedule rows for impossible amounts or confirmed rows missing dates
    scheduleRows.forEachIndexed { idx, row ->
      if (row.amountCents != null && row.amountCents <= 0L) {
        issues.add(
          ImportValidationIssue(
            fieldKey = "installmentRow_${row.id}",
            severity = ImportSeverity.ERROR,
            title = "Schedule row #${idx + 1} has invalid amount",
            message = "Installment amounts in the schedule must be positive."
          )
        )
      }
      if (row.dueDateIso != null && ManilaTime.parseDate(row.dueDateIso) == null) {
        issues.add(
          ImportValidationIssue(
            fieldKey = "installmentRow_${row.id}",
            severity = ImportSeverity.ERROR,
            title = "Schedule row #${idx + 1} has invalid date",
            message = "Enter a valid date or clear it to mark as unknown."
          )
        )
      }
    }

    // 2. Informative Financial Consistency Warnings (never auto-overwrite!)
    if (explicitBalanceCents != null &&
      explicitRemainingInstallmentTotalCents != null &&
      abs(explicitBalanceCents - explicitRemainingInstallmentTotalCents) > 100L
    ) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "balanceVsInstallmentTotal",
          severity = ImportSeverity.WARNING,
          title = "Balance vs. remaining installment total differ",
          message = "Balance is ${MoneyFormatter.formatPeso(explicitBalanceCents)}, while remaining installment total is ${MoneyFormatter.formatPeso(explicitRemainingInstallmentTotalCents)}. Check whether fees/interest explain the gap."
        )
      )
    }

    if (explicitAmountCurrentlyDueCents != null &&
      explicitBalanceCents != null &&
      explicitBalanceCents > 0L &&
      explicitAmountCurrentlyDueCents > explicitBalanceCents + 100L
    ) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "amountDueVsBalance",
          severity = ImportSeverity.WARNING,
          title = "Amount currently due exceeds remaining balance",
          message = "Amount due now (${MoneyFormatter.formatPeso(explicitAmountCurrentlyDueCents)}) is larger than remaining balance (${MoneyFormatter.formatPeso(explicitBalanceCents)}). Confirm whether fees or penalties are included."
        )
      )
    }

    if (originalInstallmentCount != null && paidInstallmentsCount != null && remainingInstallmentsCount != null) {
      val expectedRem = originalInstallmentCount - paidInstallmentsCount
      if (expectedRem >= 0 && expectedRem != remainingInstallmentsCount) {
        issues.add(
          ImportValidationIssue(
            fieldKey = "countConsistency",
            severity = ImportSeverity.WARNING,
            title = "Installment counts do not add up",
            message = "$originalInstallmentCount total minus $paidInstallmentsCount paid is $expectedRem, not $remainingInstallmentsCount remaining."
          )
        )
      }
    }

    val unpaidRows = scheduleRows.filter {
      it.status == ExtractedInstallmentStatus.UPCOMING ||
        it.status == ExtractedInstallmentStatus.OVERDUE ||
        it.status == ExtractedInstallmentStatus.UNKNOWN
    }
    val paidRows = scheduleRows.filter { it.status == ExtractedInstallmentStatus.PAID }

    if (paidInstallmentsCount != null && paidRows.isNotEmpty() && paidRows.size != paidInstallmentsCount) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "paidRowMismatch",
          severity = ImportSeverity.WARNING,
          title = "Paid count vs. paid rows differ",
          message = "Paid installments says $paidInstallmentsCount, while ${paidRows.size} row(s) in the schedule are marked Paid."
        )
      )
    }

    if (remainingInstallmentsCount != null && unpaidRows.isNotEmpty() && unpaidRows.size != remainingInstallmentsCount) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "remainingRowMismatch",
          severity = ImportSeverity.WARNING,
          title = "Remaining count vs. unpaid rows differ",
          message = "Remaining installments says $remainingInstallmentsCount, while ${unpaidRows.size} upcoming row(s) are listed."
        )
      )
    }

    if (unpaidRows.isNotEmpty() && unpaidRows.all { it.amountCents != null }) {
      val calcUnpaidSum = unpaidRows.sumOf { it.amountCents ?: 0L }
      if (remainingBalanceCents != null && remainingBalanceCents > 0L && abs(calcUnpaidSum - remainingBalanceCents) > 100L) {
        issues.add(
          ImportValidationIssue(
            fieldKey = "scheduleSumVsBalance",
            severity = ImportSeverity.WARNING,
            title = "Unpaid schedule total differs from remaining balance",
            message = "Extracted upcoming rows total ${MoneyFormatter.formatPeso(calcUnpaidSum)}, while Remaining balance is ${MoneyFormatter.formatPeso(remainingBalanceCents)}."
          )
        )
      }
      if (explicitRemainingInstallmentTotalCents != null && abs(calcUnpaidSum - explicitRemainingInstallmentTotalCents) > 100L) {
        issues.add(
          ImportValidationIssue(
            fieldKey = "scheduleSumVsRemTotal",
            severity = ImportSeverity.WARNING,
            title = "Schedule sum differs from remaining installment total",
            message = "Calculated unpaid row sum (${MoneyFormatter.formatPeso(calcUnpaidSum)}) differs from screenshot label (${MoneyFormatter.formatPeso(explicitRemainingInstallmentTotalCents)})."
          )
        )
      }
    }

    if (installmentAmountCents != null && installmentAmountCents > 0L &&
      remainingInstallmentsCount != null && remainingInstallmentsCount > 0 &&
      remainingBalanceCents != null && remainingBalanceCents > 0L
    ) {
      val product = installmentAmountCents * remainingInstallmentsCount.toLong()
      val tolerance = maxOf(100L, installmentAmountCents / 3L)
      if (abs(product - remainingBalanceCents) > tolerance && unpaidRows.isEmpty()) {
        issues.add(
          ImportValidationIssue(
            fieldKey = "installmentProductMismatch",
            severity = ImportSeverity.WARNING,
            title = "Per-payment × payments left differs from balance",
            message = "$remainingInstallmentsCount × ${MoneyFormatter.formatPeso(installmentAmountCents)} = ${MoneyFormatter.formatPeso(product)}, while balance is ${MoneyFormatter.formatPeso(remainingBalanceCents)}."
          )
        )
      }
    }

    val missingScheduleDates = unpaidRows.count { it.dueDateIso == null }
    val missingScheduleAmounts = unpaidRows.count { it.amountCents == null }
    if (missingScheduleDates > 0 || missingScheduleAmounts > 0) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "scheduleRowGaps",
          severity = ImportSeverity.WARNING,
          title = "Some schedule rows have missing details",
          message = buildString {
            if (missingScheduleDates > 0) append("$missingScheduleDates row(s) missing a due date. ")
            if (missingScheduleAmounts > 0) append("$missingScheduleAmounts row(s) missing an amount.")
          }.trim()
        )
      )
    }

    val dupDates = unpaidRows.mapNotNull { it.dueDateIso }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (dupDates.isNotEmpty()) {
      issues.add(
        ImportValidationIssue(
          fieldKey = "duplicateScheduleDates",
          severity = ImportSeverity.WARNING,
          title = "Duplicate dates in schedule",
          message = "Multiple unpaid rows share the same date (${dupDates.joinToString { ManilaTime.formatDisplay(it) }}). Verify before saving."
        )
      )
    }

    return issues
  }
}
