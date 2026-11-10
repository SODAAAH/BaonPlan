package com.baonplan.finance.domain.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.domain.util.ManilaTime
import kotlin.math.abs

/**
 * Pure Kotlin privacy-preserving local learning engine.
 * Extracts only generic UI terminology overrides and provider format preferences
 * from confirmed user edits during scan review — never storing peso amounts,
 * dates, loan names, reference codes, or personal identifiers.
 */
object ScannerCorrectionLearner {

  private const val MAX_LABEL_OVERRIDES = 40
  private const val MAX_PROVIDER_ENTRIES = 30

  fun learnFromSave(
    currentPrefs: LearnedScannerPreferences,
    initialDraft: DebtImportDraft,
    confirmedDebt: DebtEntity
  ): Pair<LearnedScannerPreferences, Boolean> {
    var updatedLabels = currentPrefs.labelOverrides
    var updatedDateOrder = currentPrefs.providerDateOrder
    var updatedCategoryDefaults = currentPrefs.providerCategoryDefaults
    var didLearn = false

    // 1. Learn generic label-to-field overrides when user swaps/corrects a uniquely matched cue
    val mutableLabels = updatedLabels.toMutableMap()
    val cues = initialDraft.detectedLabelCues

    val monetaryFieldChecks: List<Triple<LoanFieldKey, Long?, Long?>> = listOf(
      Triple(
        LoanFieldKey.OUTSTANDING_BALANCE,
        initialDraft.outstandingBalanceCents.value ?: initialDraft.remainingInstallmentTotalCents.value,
        confirmedDebt.remainingBalanceCents
      ),
      Triple(
        LoanFieldKey.ORIGINAL_PRINCIPAL,
        initialDraft.originalPrincipalCents.value,
        confirmedDebt.originalPrincipalCents
      ),
      Triple(
        LoanFieldKey.INSTALLMENT_AMOUNT,
        initialDraft.installmentAmountCents.value,
        confirmedDebt.installmentAmountCents.takeIf { it > 0L }
      ),
      Triple(
        LoanFieldKey.TOTAL_REPAYMENT,
        initialDraft.totalRepaymentCents.value,
        confirmedDebt.totalScheduledRepaymentCents
      ),
      Triple(
        LoanFieldKey.INTEREST,
        initialDraft.interestCents.value,
        confirmedDebt.interestCents
      ),
      Triple(
        LoanFieldKey.SERVICE_FEE,
        initialDraft.serviceFeeCents.value,
        confirmedDebt.serviceFeeCents
      ),
      Triple(
        LoanFieldKey.PROCESSING_FEE,
        initialDraft.processingFeeCents.value,
        confirmedDebt.processingFeeCents
      ),
      Triple(
        LoanFieldKey.AMOUNT_RECEIVED,
        initialDraft.amountReceivedCents.value,
        confirmedDebt.amountReceivedCents
      )
    )

    for ((targetField, initialVal, confirmedVal) in monetaryFieldChecks) {
      if (confirmedVal == null || confirmedVal <= 0L) continue
      if (initialVal != null && abs(initialVal - confirmedVal) <= 50L) continue

      val matchingCues = cues.filter { cue ->
        cue.moneyCents != null &&
          abs(cue.moneyCents - confirmedVal) <= 50L &&
          cue.assignedField != targetField &&
          isSafeGenericLabel(cue.normalizedLabel)
      }
      val distinctLabels = matchingCues.map { it.normalizedLabel }.distinct()
      if (distinctLabels.size == 1) {
        val labelKey = distinctLabels.single()
        if (mutableLabels[labelKey] != targetField) {
          mutableLabels[labelKey] = targetField
          didLearn = true
        }
      }
    }

    // Also support learning integer count labels if swapped
    val initialRemCount = initialDraft.remainingInstallmentsCount.value
    val confirmedRemCount = confirmedDebt.remainingInstallmentsCount
    if (confirmedRemCount > 0 && initialRemCount != confirmedRemCount) {
      val matchingIntCues = cues.filter { cue ->
        cue.intValue == confirmedRemCount &&
          cue.assignedField != LoanFieldKey.REMAINING_COUNT &&
          isSafeGenericLabel(cue.normalizedLabel)
      }
      val distinctIntLabels = matchingIntCues.map { it.normalizedLabel }.distinct()
      if (distinctIntLabels.size == 1) {
        val labelKey = distinctIntLabels.single()
        if (mutableLabels[labelKey] != LoanFieldKey.REMAINING_COUNT) {
          mutableLabels[labelKey] = LoanFieldKey.REMAINING_COUNT
          didLearn = true
        }
      }
    }

    if (mutableLabels.size > MAX_LABEL_OVERRIDES) {
      val excess = mutableLabels.size - MAX_LABEL_OVERRIDES
      val trimmed = mutableLabels.entries.drop(excess).associate { it.key to it.value }
      updatedLabels = trimmed
    } else {
      updatedLabels = mutableLabels
    }

    // 2. Learn numeric date order (MM/DD vs DD/MM) per lender provider
    val pid = initialDraft.providerProfileId?.takeIf { it.isNotBlank() }
    val initDate = ManilaTime.parseDate(initialDraft.nextDueDateIso.value)
    val savedDate = ManilaTime.parseDate(confirmedDebt.nextDueDate)
    if (pid != null && initDate != null && savedDate != null && initDate != savedDate) {
      val isMonthDaySwap = initDate.year == savedDate.year &&
        initDate.monthValue == savedDate.dayOfMonth &&
        initDate.dayOfMonth == savedDate.monthValue &&
        initDate.monthValue != initDate.dayOfMonth
      if (isMonthDaySwap) {
        val inferredOrder = if (initialDraft.numericDateOrderUsed == NumericDateOrder.DAY_FIRST) {
          NumericDateOrder.MONTH_FIRST
        } else {
          NumericDateOrder.DAY_FIRST
        }
        val nextMap = updatedDateOrder.toMutableMap()
        if (nextMap[pid] != inferredOrder.name) {
          nextMap[pid] = inferredOrder.name
          updatedDateOrder = capMapSize(nextMap, MAX_PROVIDER_ENTRIES)
          didLearn = true
        }
      }
    }

    // 3. Learn preferred debt category per provider (e.g. changing default "Installment Loan" -> "Tuition")
    val initialCat = initialDraft.loanType.value?.takeIf { it.isNotBlank() } ?: "Installment Loan"
    val confirmedCat = confirmedDebt.category.trim()
    if (pid != null &&
      confirmedCat.isNotBlank() &&
      !confirmedCat.equals(initialCat, ignoreCase = true) &&
      isSafeCategoryLabel(confirmedCat, confirmedDebt.name)
    ) {
      val nextCats = updatedCategoryDefaults.toMutableMap()
      if (nextCats[pid] != confirmedCat) {
        nextCats[pid] = confirmedCat
        updatedCategoryDefaults = capMapSize(nextCats, MAX_PROVIDER_ENTRIES)
        didLearn = true
      }
    }

    if (!didLearn) return currentPrefs to false

    val resultPrefs = LearnedScannerPreferences(
      labelOverrides = updatedLabels,
      providerDateOrder = updatedDateOrder,
      providerCategoryDefaults = updatedCategoryDefaults,
      correctionCount = currentPrefs.correctionCount + 1
    )
    return resultPrefs to true
  }

  private fun isSafeGenericLabel(label: String): Boolean {
    if (label.length !in 3..40) return false
    if (!label.any { it.isLetter() }) return false
    if (label.any { it.isDigit() }) return false
    val wordCount = label.split(' ').count { it.isNotBlank() }
    return wordCount in 1..6
  }

  private fun isSafeCategoryLabel(category: String, debtName: String): Boolean {
    if (category.length !in 2..32) return false
    if (!category.any { it.isLetter() }) return false
    if (category.any { it.isDigit() }) return false
    if (category.equals(debtName.trim(), ignoreCase = true)) return false
    val words = category.split(Regex("""\s+""")).filter { it.isNotBlank() }
    return words.size in 1..3
  }

  private fun <K, V> capMapSize(map: MutableMap<K, V>, maxEntries: Int): Map<K, V> {
    if (map.size <= maxEntries) return map.toMap()
    val excess = map.size - maxEntries
    return map.entries.drop(excess).associate { it.key to it.value }
  }
}

