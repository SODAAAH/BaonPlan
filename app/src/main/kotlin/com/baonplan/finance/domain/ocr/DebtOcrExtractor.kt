package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.RepaymentFrequency
import com.baonplan.finance.domain.util.ManilaTime
import com.baonplan.finance.domain.util.MoneyFormatter
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin multi-stage structured financial interpreter that parses one OCR document page
 * using 2D cell topology (same-row, 2-column grid below, hero-above-label), product-family
 * adapters, learned local overrides, and audited OCR glyph repair before invoking
 * [FinancialReasoningEngine].
 */
object DebtOcrExtractor {

  private val prefixedPesoRegex = Regex(
    """(?:₱|PHP|Php|PhP|(?<![A-Za-z])Ph\.?|(?<![A-Za-z])P\.?(?=\s*[\dOlI])|(?<![A-Za-z])(?:F|£)(?=\s*\d[\d,\sOIl]*[.,]\d{2}))\s*([0-9OlI]{1,3}(?:[,\s][0-9OlI]{3})+(?:[.,]\s?[0-9OlI]{1,2})?|[0-9OlI]{1,3}(?:\.[0-9OlI]{3})+(?:\.[0-9OlI]{2})|[0-9OlI]+(?:[.,]\s?[0-9OlI]{1,2})?)(?![\d%]|\.\d|,\d)"""
  )

  private val bareDecimalMoneyRegex = Regex(
    """(?<![A-Za-z0-9./-])([0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{1,2}|[0-9]{1,3}(?:\.[0-9]{3})+\.[0-9]{2}|[0-9]+\.[0-9]{2}|[0-9]{1,3},[0-9]{2})(?![A-Za-z0-9%./-]|\.\d|,\d)"""
  )

  private val bareLabeledMoneyRegex = Regex(
    """(?<![A-Za-z0-9./-])([0-9]{1,3}(?:,[0-9]{3})+(?:[.,][0-9]{1,2})?|[0-9]+[.,][0-9]{2}|[0-9]{2,6})(?![A-Za-z0-9%./-])"""
  )

  // Matches "₱51.78 x 24" or "₱51.78 × 24" or "51.78 * 24 mos"
  private val installmentMultiplierRegex = Regex(
    """(?:₱|PHP|Php|P\.?)?\s*([0-9OlI]{1,3}(?:,[0-9OlI]{3})*(?:[.,][0-9OlI]{1,2})?|[0-9OlI]+(?:[.,][0-9OlI]{1,2})?)\s*[x×*]\s*(\d{1,3})\b""",
    RegexOption.IGNORE_CASE
  )

  private val percentageRegex = Regex("""\d+(?:\.\d+)?\s*%""")

  private val paidFractionRegex = Regex(
    """(?:paid\s*[:\-|]?\s*)?(\d{1,2})\s*(?:/|of)\s*(\d{1,2})\s*(paid|installments?|payments?|months?|mos\.?)?""",
    RegexOption.IGNORE_CASE
  )

  private val remainingFractionRegex = Regex(
    """(\d{1,2})\s*(?:/|of)\s*(\d{1,2})\s*(?:left|remaining|unpaid)""",
    RegexOption.IGNORE_CASE
  )

  private val refIdLabelRegex = Regex(
    """(?:loan\s*(?:id|no\.?|number|#)|reference\s*(?:id|no\.?|number|#)?|ref\.?\s*(?:id|no\.?|number|#)|order\s*(?:id|no\.?|number|#)|contract\s*(?:id|no\.?|number|#)|account\s*(?:id|no\.?|number|#)|transaction\s*(?:id|no\.?|number|#))\s*[:\-]?\s*([A-Za-z0-9][A-Za-z0-9\-_/#]{2,28})""",
    RegexOption.IGNORE_CASE
  )

  private val inlineOrderHashRegex = Regex(
    """\b(?:order|loan|contract|ref)\s*#\s*([A-Za-z0-9\-_]{3,24})\b""",
    RegexOption.IGNORE_CASE
  )

  // Standalone "#46008372" reference badge in card headers (at least 5 chars so "#1".."#36" ordinals aren't matched)
  private val standaloneHeaderHashCodeRegex = Regex(
    """(?<![A-Za-z0-9])#([A-Za-z0-9\-_]{5,24})\b"""
  )

  private val nonScheduleDateHeaderKeywords = listOf(
    "loan date", "date granted", "disbursed", "disbursement", "booking date",
    "created", "generated", "approved", "printed", "as of", "issued",
    "application date", "start date", "applied on", "statement date",
    "sent to", "credited to", "effective date"
  )

  private val bookingDateKeywords = listOf(
    "loan date", "booking date", "date granted", "disbursed", "disbursement date",
    "applied on", "application date", "sent to", "credited to", "availed on"
  )

  private val documentRecencyKeywords = listOf(
    "as of", "statement date", "generated", "printed", "updated on", "snapshot"
  )

  private val universalNoisePhrases = listOf(
    "wallet balance", "cashback", "voucher", "gscore", "promo", "pre-approved", "up to "
  )

  fun extract(
    page: OcrDocumentPage,
    referenceDate: LocalDate = ManilaTime.today(),
    learnedPrefs: LearnedScannerPreferences = LearnedScannerPreferences()
  ): SingleScreenshotExtraction {
    val normalizedPage = OcrLayoutNormalizer.normalizeWithAudits(page)
    val rows = normalizedPage.rows
    val textAudits = normalizedPage.audits
    if (rows.isEmpty()) {
      return SingleScreenshotExtraction(
        screenshotIndex = page.screenshotIndex,
        documentKind = ExtractedDocumentKind.UNSTRUCTURED,
        textCorrections = textAudits,
        averageOcrConfidence = page.quality.averageConfidence
      )
    }

    val idx = page.screenshotIndex
    val rowTexts = rows.map { it.text }
    val rawCombined = page.rawFullText.ifBlank { rowTexts.joinToString("\n") }
    val adapterResult = ProviderProductAdapters.classify(rows, rawCombined, idx)
    val providerProfile = adapterResult.providerProfile

    val inferredOrder = FlexibleDateParser.inferNumericOrder(rowTexts)
    val effectiveNumericOrder = when {
      inferredOrder != NumericDateOrder.AMBIGUOUS -> inferredOrder
      providerProfile != null -> {
        val learnedOrderName = learnedPrefs.providerDateOrder[providerProfile.id]
        val learnedOrder = runCatching {
          learnedOrderName?.let { NumericDateOrder.valueOf(it) }
        }.getOrNull()
        learnedOrder?.takeIf { it != NumericDateOrder.AMBIGUOUS }
          ?: providerProfile.preferredNumericDateOrder
      }
      else -> NumericDateOrder.AMBIGUOUS
    }

    val docYears = FlexibleDateParser.extractDocumentYears(rowTexts)
    val contextYear = docYears.singleOrNull() ?: docYears.maxOrNull()

    val issues = mutableListOf<ImportValidationIssue>()
    val labelCues = mutableListOf<DetectedLabelValueCue>()

    // 1. Lender, loan type, reference ID, loan name, header hero principal
    val lenderResult = extractLenderAndType(rows, idx, providerProfile, learnedPrefs)
    val lenderField = lenderResult.first
    var loanTypeField = lenderResult.second
    val refIdField = extractReferenceId(rows, idx)
    var loanNameField = extractLoanName(rows, idx, lenderField.value, refIdField.value)

    // 2. Monetary & credit-line key-value fields (Strict semantic separation)
    var originalPrincipal = ExtractedField.notDetected<Long>()
    var disbursedAmount = ExtractedField.notDetected<Long>()
    var amountReceived = ExtractedField.notDetected<Long>()
    var outstandingBalance = ExtractedField.notDetected<Long>()
    var amountCurrentlyDue = ExtractedField.notDetected<Long>()
    var remainingInstallmentTotal = ExtractedField.notDetected<Long>()
    var pastDueAmount = ExtractedField.notDetected<Long>()
    var unpaidCharges = ExtractedField.notDetected<Long>()
    var creditLimit = ExtractedField.notDetected<Long>()
    var availableCredit = ExtractedField.notDetected<Long>()
    var usedCredit = ExtractedField.notDetected<Long>()
    var statementBalance = ExtractedField.notDetected<Long>()
    var minimumDue = ExtractedField.notDetected<Long>()
    var statementDateIso = ExtractedField.notDetected<String>()
    var statementPeriod = ExtractedField.notDetected<String>()
    var loanBookingDateIso = ExtractedField.notDetected<String>()

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
    var isNextDueExplicit = false
    var finalDueDate = ExtractedField.notDetected<String>()
    var lastPaidDate = ExtractedField.notDetected<String>()
    var documentDateIso: String? = null
    var scheduleEffectiveDateIso: String? = null

    var explicitFrequency = ExtractedField.notDetected<RepaymentFrequency>()
    var customIntervalDays = ExtractedField.notDetected<Int>()
    var loanStatus = ExtractedField.notDetected<DebtStatus>()

    val claimedValueCells = mutableSetOf<Pair<Int, Int>>()

    // Check top header rows for inline hero principal + loan title (e.g. "₱1,000.00 GCash Cash Loan | #46008372")
    extractHeaderHeroPrincipal(rows, idx)?.let { heroPrincipal ->
      originalPrincipal = heroPrincipal
    }

    for (rowIdx in rows.indices) {
      val row = rows[rowIdx]
      val rowText = row.text
      val rowLower = canonicalizeKeywords(rowText)

      // Check fraction badges ("2/6 paid", "Paid: 3/24", "Paid: | 3/24", "4/6 remaining")
      val fractionInput = rowText.replace(Regex("""\s*\|\s*"""), " ")
      extractCountFractions(
        text = fractionInput,
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
          containsAny(rowLower, "fully paid", "paid in full", "loan settled", "status: paid", "status | paid", "status: completed", "status | completed") ->
            loanStatus = ExtractedField.extracted(DebtStatus.PAID, rowText, idx, verificationReason = "Loan status shown as Paid in screenshot #${idx + 1}.")
          containsAny(rowLower, "overdue", "past due", "active", "ongoing", "in repayment") &&
            !containsAny(rowLower, "if overdue", "late fee", "when overdue") ->
            loanStatus = ExtractedField.extracted(DebtStatus.ACTIVE, rowText, idx, verificationReason = "Loan status shown as active in screenshot #${idx + 1}.")
        }
      }

      // Check explicit frequency / custom interval text
      if (explicitFrequency.value == null && !rowLower.contains("interest") && !rowLower.contains("fee")) {
        parseExplicitFrequency(rowText, idx)?.let { (freq, days) ->
          explicitFrequency = freq
          if (days != null && customIntervalDays.value == null) {
            customIntervalDays = ExtractedField.extracted(days, rowText, idx, verificationReason = "Explicit interval days read from screenshot #${idx + 1}.")
          }
        }
      }

      // Check loan type override if explicit label present
      if (containsAny(rowLower, "loan type", "product type", "plan type", "facility")) {
        val afterColon = extractRightSideText(row)
        if (!afterColon.isNullOrBlank()) {
          loanTypeField = ExtractedField.extracted(afterColon, rowText, idx, verificationReason = "Loan type label found in screenshot #${idx + 1}.")
        }
      }

      // Check statement period ("Billing period: Oct 1 - Oct 31, 2026")
      if (statementPeriod.value == null && containsAny(rowLower, "statement period", "billing period", "billing cycle")) {
        val periodText = extractRightSideText(row) ?: rowText.substringAfter(':', "").trim()
        if (periodText.isNotBlank()) {
          statementPeriod = ExtractedField.extracted(
            value = periodText,
            sourceSnippet = rowText,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Statement billing period read from screenshot #${idx + 1}."
          )
        }
      }

      // Check snapshot/document recency date ("As of", "Statement Date", etc.)
      if (documentRecencyKeywords.any { rowLower.contains(it) }) {
        val docDate = resolveDateForRowOrCells(rows, rowIdx, 0, effectiveNumericOrder, contextYear, referenceDate)?.first
        if (docDate != null) {
          if (documentDateIso == null) documentDateIso = docDate.isoDate
          if (statementDateIso.value == null && rowLower.contains("statement date")) {
            statementDateIso = ExtractedField.extracted(
              value = docDate.isoDate,
              sourceSnippet = rowText,
              screenshotIndex = idx,
              confidence = docDate.confidence,
              verificationReason = "Statement date read from screenshot #${idx + 1}."
            )
          }
        }
      }

      // Check booking / disbursement date ("Loan date", "Disbursed", "Sent to +63... / Oct 23, 2026")
      if (loanBookingDateIso.value == null && bookingDateKeywords.any { rowLower.contains(it) }) {
        val bookDate = resolveDateForRowOrCells(rows, rowIdx, 0, effectiveNumericOrder, contextYear, referenceDate)
        if (bookDate != null) {
          loanBookingDateIso = ExtractedField.extracted(
            value = bookDate.first.isoDate,
            sourceSnippet = bookDate.second,
            screenshotIndex = idx,
            confidence = bookDate.first.confidence,
            verificationReason = "Loan booking / disbursement date read from screenshot #${idx + 1}."
          )
        }
      }

      // Check restructured / Flexi effective date
      if (scheduleEffectiveDateIso == null && containsAny(rowLower, "effective date", "effective on", "restructured on", "new schedule")) {
        val effDate = resolveDateForRowOrCells(rows, rowIdx, 0, effectiveNumericOrder, contextYear, referenceDate)?.first
        if (effDate != null) {
          scheduleEffectiveDateIso = effDate.isoDate
        }
      }

      val isLikelyScheduleRow = looksLikeScheduleRow(row, effectiveNumericOrder, contextYear, referenceDate, providerProfile)
      val cells = row.cells.ifEmpty {
        listOf(OcrCell(row.text, row.left, row.top, row.right, row.bottom, row.averageConfidence))
      }

      for (cellIdx in cells.indices) {
        val cell = cells[cellIdx]
        val cellText = cell.text
        val cellLower = canonicalizeKeywords(cellText)

        if (containsNoisePhrase(cellLower, providerProfile, adapterResult.productClassification.value)) continue

        // Summary dates per cell
        if (!isLikelyScheduleRow || containsAny(cellLower, "next payment", "next due", "due date", "final payment", "maturity", "last payment", "last paid")) {
          if (nextDueDate.value == null &&
            containsAny(cellLower, "next payment due", "next due date", "next payment date", "next payment", "next due", "payment due date", "due on", "due date") &&
            !containsAny(cellLower, "final", "last payment", "maturity", "end date", "first due")
          ) {
            resolveDateForRowOrCells(rows, rowIdx, cellIdx, effectiveNumericOrder, contextYear, referenceDate)?.let { (d, src) ->
              val dateAlts = if (d.isAmbiguousNumericOrder) {
                val parsed = ManilaTime.parseDate(d.isoDate)
                if (parsed != null && parsed.monthValue != parsed.dayOfMonth && parsed.dayOfMonth <= 12) {
                  val swapped = runCatching { LocalDate.of(parsed.year, parsed.dayOfMonth, parsed.monthValue) }.getOrNull()
                  if (swapped != null) {
                    val swappedIso = ManilaTime.formatIso(swapped)
                    listOf(
                      FieldAlternative(
                        value = swappedIso,
                        label = "${ManilaTime.formatDisplay(swappedIso)} (if MM/DD vs DD/MM swapped)",
                        origin = FieldOrigin.EXTRACTED,
                        screenshotIndex = idx,
                        sourceSnippet = src
                      )
                    )
                  } else emptyList()
                } else emptyList()
              } else emptyList()
              nextDueDate = ExtractedField.extracted(
                value = d.isoDate,
                sourceSnippet = src,
                screenshotIndex = idx,
                confidence = adjustConfidenceWithLineQuality(d.confidence, cell.confidence),
                verificationReason = d.note ?: "Next payment date read next to due-date label in screenshot #${idx + 1}.",
                alternatives = dateAlts
              )
              isNextDueExplicit = true
            }
          }

          if (finalDueDate.value == null &&
            containsAny(cellLower, "final payment", "final due", "last due date", "maturity date", "end date", "last installment date")
          ) {
            resolveDateForRowOrCells(rows, rowIdx, cellIdx, effectiveNumericOrder, contextYear, referenceDate)?.let { (d, src) ->
              finalDueDate = ExtractedField.extracted(
                value = d.isoDate,
                sourceSnippet = src,
                screenshotIndex = idx,
                confidence = adjustConfidenceWithLineQuality(d.confidence, cell.confidence),
                verificationReason = d.note ?: "Final scheduled payment date found in screenshot #${idx + 1}."
              )
            }
          }

          if (lastPaidDate.value == null &&
            containsAny(cellLower, "last paid", "last payment made", "latest payment", "last settled", "previous payment")
          ) {
            resolveDateForRowOrCells(rows, rowIdx, cellIdx, effectiveNumericOrder, contextYear, referenceDate)?.let { (d, src) ->
              lastPaidDate = ExtractedField.extracted(
                value = d.isoDate,
                sourceSnippet = src,
                screenshotIndex = idx,
                confidence = adjustConfidenceWithLineQuality(d.confidence, cell.confidence),
                verificationReason = d.note ?: "Most recent completed payment date read from screenshot #${idx + 1}."
              )
            }
          }
        }

        // Integer counts per cell
        if (explicitOriginalCount.value == null &&
          containsAny(cellLower, "original installments", "total installments", "number of installments", "total payments", "installment count", "loan term", "tenor")
        ) {
          val countMatch = resolveIntegerForCell(rows, rowIdx, cellIdx, claimedValueCells)
          if (countMatch != null && countMatch.first in 1..120) {
            val isTenorMonths = containsAny(cellLower, "tenor", "loan term") &&
              (cellLower.contains("month") || rowLower.contains("month"))
            val cueLabel = extractLabelPortion(cellLower)
            labelCues.add(DetectedLabelValueCue(cueLabel, LoanFieldKey.ORIGINAL_COUNT, intValue = countMatch.first, screenshotIndex = idx))
            explicitOriginalCount = ExtractedField.extracted(
              value = countMatch.first,
              sourceSnippet = countMatch.second,
              screenshotIndex = idx,
              confidence = adjustConfidenceWithLineQuality(
                if (isTenorMonths) ExtractionConfidence.MEDIUM else ExtractionConfidence.HIGH,
                cell.confidence
              ),
              verificationReason = if (isTenorMonths) {
                "Read from tenor/months label — confirm exact installment count."
              } else {
                "Total installments read from screenshot #${idx + 1}."
              }
            )
          }
        }

        if (explicitPaidCount.value == null &&
          containsAny(cellLower, "installments paid", "paid installments", "payments made", "already paid count", "completed installments")
        ) {
          val countMatch = resolveIntegerForCell(rows, rowIdx, cellIdx, claimedValueCells)
          if (countMatch != null && countMatch.first in 0..120) {
            labelCues.add(DetectedLabelValueCue(extractLabelPortion(cellLower), LoanFieldKey.PAID_COUNT, intValue = countMatch.first, screenshotIndex = idx))
            explicitPaidCount = ExtractedField.extracted(
              value = countMatch.first,
              sourceSnippet = countMatch.second,
              screenshotIndex = idx,
              confidence = adjustConfidenceWithLineQuality(ExtractionConfidence.HIGH, cell.confidence),
              verificationReason = "Paid installments count read from screenshot #${idx + 1}."
            )
          }
        }

        if (explicitRemainingCount.value == null &&
          containsAny(cellLower, "remaining installments", "installments left", "payments left", "unpaid installments", "remaining payments") &&
          !containsAny(cellLower, "total")
        ) {
          val countMatch = resolveIntegerForCell(rows, rowIdx, cellIdx, claimedValueCells)
          if (countMatch != null && countMatch.first in 0..120) {
            labelCues.add(DetectedLabelValueCue(extractLabelPortion(cellLower), LoanFieldKey.REMAINING_COUNT, intValue = countMatch.first, screenshotIndex = idx))
            explicitRemainingCount = ExtractedField.extracted(
              value = countMatch.first,
              sourceSnippet = countMatch.second,
              screenshotIndex = idx,
              confidence = adjustConfidenceWithLineQuality(ExtractionConfidence.HIGH, cell.confidence),
              verificationReason = "Remaining installments count read from screenshot #${idx + 1}."
            )
          }
        }

        // Check installment multiplier pair ("Installment | ₱51.78 x 24") before standard single-money resolution
        if (!isLikelyScheduleRow && isInstallmentLabel(cellLower)) {
          val mult = resolveInstallmentMultiplierForCell(rows, rowIdx, cellIdx, claimedValueCells)
          if (mult != null) {
            val (perInstCents, countN, multSnippet) = mult
            if (installmentAmount.value == null) {
              installmentAmount = ExtractedField.extracted(
                value = perInstCents,
                sourceSnippet = multSnippet,
                screenshotIndex = idx,
                confidence = adjustConfidenceWithLineQuality(ExtractionConfidence.HIGH, cell.confidence),
                verificationReason = "Per-installment amount read from \"$multSnippet\" in screenshot #${idx + 1}."
              )
            }
            if (explicitOriginalCount.value == null && countN in 1..120) {
              explicitOriginalCount = ExtractedField.extracted(
                value = countN,
                sourceSnippet = multSnippet,
                screenshotIndex = idx,
                confidence = adjustConfidenceWithLineQuality(ExtractionConfidence.HIGH, cell.confidence),
                verificationReason = "Total installment count ($countN) read from \"$multSnippet\" in screenshot #${idx + 1}."
              )
            }
            continue
          }
        }

        // Monetary field interpretation for this cell
        val fieldKey = classifyMonetaryLabelForCell(
          cellLower = cellLower,
          isLikelyScheduleRow = isLikelyScheduleRow,
          providerProfile = providerProfile,
          productClassification = adapterResult.productClassification.value,
          hasInstallmentContext = installmentAmount.value != null || explicitOriginalCount.value != null,
          learnedPrefs = learnedPrefs
        ) ?: continue

        val resolvedMoney = resolveMoneyForCell(
          rows = rows,
          rowIdx = rowIdx,
          cellIdx = cellIdx,
          claimedValueCells = claimedValueCells,
          providerProfile = providerProfile,
          allowBare = !isLikelyScheduleRow
        ) ?: continue

        val (moneyMatch, srcSnippet, valConf) = resolvedMoney
        val combinedConf = adjustConfidenceWithLineQuality(
          minConfidence(moneyMatch.confidence, valConf),
          cell.confidence
        )
        val cueLabel = extractLabelPortion(cellLower)
        if (cueLabel.isNotBlank()) {
          labelCues.add(
            DetectedLabelValueCue(
              normalizedLabel = cueLabel,
              assignedField = fieldKey,
              moneyCents = moneyMatch.cents,
              screenshotIndex = idx
            )
          )
        }

        when (fieldKey) {
          LoanFieldKey.REMAINING_INSTALLMENT_TOTAL -> if (remainingInstallmentTotal.value == null) {
            remainingInstallmentTotal = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from remaining installment total label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.OUTSTANDING_BALANCE, LoanFieldKey.OUTSTANDING_PRINCIPAL -> if (outstandingBalance.value == null) {
            outstandingBalance = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from outstanding balance label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.AMOUNT_CURRENTLY_DUE -> if (amountCurrentlyDue.value == null) {
            amountCurrentlyDue = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read as current payment/cycle amount due in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.PAST_DUE_AMOUNT -> if (pastDueAmount.value == null) {
            pastDueAmount = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from past-due amount label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.UNPAID_CHARGES -> if (unpaidCharges.value == null) {
            unpaidCharges = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from unpaid charges / late penalty label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.CREDIT_LIMIT -> if (creditLimit.value == null) {
            creditLimit = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from credit limit label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.AVAILABLE_CREDIT -> if (availableCredit.value == null) {
            availableCredit = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from available credit label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.USED_CREDIT -> if (usedCredit.value == null) {
            usedCredit = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from used credit label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.STATEMENT_BALANCE -> if (statementBalance.value == null) {
            statementBalance = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from statement balance label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.MINIMUM_DUE -> if (minimumDue.value == null) {
            minimumDue = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from minimum amount due label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.ORIGINAL_PRINCIPAL -> if (originalPrincipal.value == null) {
            originalPrincipal = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from original loan principal label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.DISBURSED_AMOUNT -> if (disbursedAmount.value == null) {
            disbursedAmount = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from disbursed amount label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.AMOUNT_RECEIVED -> if (amountReceived.value == null) {
            amountReceived = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from net proceeds/received amount in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.TOTAL_REPAYMENT -> if (totalRepayment.value == null) {
            totalRepayment = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from total repayment label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.INSTALLMENT_AMOUNT -> if (installmentAmount.value == null && !isLikelyScheduleRow) {
            installmentAmount = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from per-installment payment label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.INTEREST -> if (interest.value == null) {
            interest = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from interest amount label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.SERVICE_FEE -> if (serviceFee.value == null) {
            serviceFee = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from service fee label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.PROCESSING_FEE -> if (processingFee.value == null) {
            processingFee = ExtractedField.extracted(
              value = moneyMatch.cents,
              sourceSnippet = srcSnippet,
              screenshotIndex = idx,
              confidence = combinedConf,
              verificationReason = moneyMatch.note ?: "Read from processing fee label in screenshot #${idx + 1}."
            )
          }
          LoanFieldKey.OTHER_FEES -> {
            otherFeeTotalAccumulator += moneyMatch.cents
            otherFeeSnippets.add(srcSnippet)
            otherFees = ExtractedField.extracted(
              value = otherFeeTotalAccumulator,
              sourceSnippet = otherFeeSnippets.joinToString("; "),
              screenshotIndex = idx,
              confidence = if (otherFeeSnippets.size > 1) ExtractionConfidence.MEDIUM else combinedConf,
              verificationReason = moneyMatch.note
                ?: if (otherFeeSnippets.size > 1) {
                  "Summed ${otherFeeSnippets.size} additional fee items in screenshot #${idx + 1}."
                } else {
                  "Read from fee label in screenshot #${idx + 1}."
                }
            )
          }
          else -> {}
        }
      }
    }

    // 3. Extract repayment schedule rows in order
    val extractedRows = extractScheduleRows(
      rows = rows,
      numericOrder = effectiveNumericOrder,
      contextYear = contextYear,
      referenceDate = referenceDate,
      screenshotIndex = idx,
      providerProfile = providerProfile
    )

    val paidRows = extractedRows.filter { it.status == ExtractedInstallmentStatus.PAID }
    val nonPaidRows = extractedRows.filter { it.status.isUnpaidObligation }

    if (lastPaidDate.value == null && paidRows.isNotEmpty()) {
      val latestPaid = paidRows.lastOrNull { it.dueDateIso != null }
      if (latestPaid != null) {
        lastPaidDate = ExtractedField.extracted(
          value = latestPaid.dueDateIso!!,
          sourceSnippet = latestPaid.sourceSnippet,
          screenshotIndex = idx,
          confidence = latestPaid.dateConfidence,
          verificationReason = latestPaid.verificationNote
            ?: "Latest payment marked Paid in schedule (screenshot #${idx + 1})."
        )
      }
    }

    val hasTruncationCue = rowTexts.any {
      Regex("""\b(?:showing\s+\d+\s+of\s+\d+|view\s+all|see\s+all|see\s+more|scroll\s+for\s+more)\b""", RegexOption.IGNORE_CASE)
        .containsMatchIn(it)
    }
    val isSchedulePartial = hasTruncationCue ||
      (explicitOriginalCount.value != null && extractedRows.isNotEmpty() && extractedRows.size < explicitOriginalCount.value) ||
      (explicitPaidCount.value != null && paidRows.size != explicitPaidCount.value) ||
      (explicitRemainingCount.value != null && nonPaidRows.isNotEmpty() && nonPaidRows.size < explicitRemainingCount.value)

    val kind = classifyDocumentKind(
      hasSummaryScalars = originalPrincipal.value != null ||
        outstandingBalance.value != null ||
        amountCurrentlyDue.value != null ||
        remainingInstallmentTotal.value != null ||
        statementBalance.value != null ||
        creditLimit.value != null ||
        installmentAmount.value != null ||
        interest.value != null ||
        serviceFee.value != null ||
        totalRepayment.value != null,
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
      loanNameField = ExtractedField.estimated(
        value = suggestion,
        sourceSnippet = lenderField.sourceSnippet,
        screenshotIndex = idx,
        confidence = ExtractionConfidence.MEDIUM,
        verificationReason = "Suggested from detected lender — rename if you track multiple loans from ${lenderField.value}."
      )
    }

    val rawExtraction = SingleScreenshotExtraction(
      screenshotIndex = idx,
      documentKind = kind,
      providerProfileId = providerProfile?.id,
      productClassification = adapterResult.productClassification,
      applicationProvider = adapterResult.applicationProvider,
      actualCreditor = adapterResult.actualCreditor,
      lender = lenderField,
      loanName = loanNameField,
      loanType = loanTypeField,
      loanReferenceId = refIdField,
      loanBookingDateIso = loanBookingDateIso,
      originalPrincipalCents = originalPrincipal,
      disbursedAmountCents = disbursedAmount,
      amountReceivedCents = amountReceived,
      outstandingBalanceCents = outstandingBalance,
      amountCurrentlyDueCents = amountCurrentlyDue,
      remainingInstallmentTotalCents = remainingInstallmentTotal,
      pastDueAmountCents = pastDueAmount,
      unpaidChargesCents = unpaidCharges,
      creditLimitCents = creditLimit,
      availableCreditCents = availableCredit,
      usedCreditCents = usedCredit,
      statementBalanceCents = statementBalance,
      minimumDueCents = minimumDue,
      statementDateIso = statementDateIso,
      statementPeriod = statementPeriod,
      installmentAmountCents = installmentAmount,
      originalInstallmentCount = explicitOriginalCount,
      paidInstallmentsCount = explicitPaidCount,
      remainingInstallmentsCount = explicitRemainingCount,
      frequency = explicitFrequency,
      customIntervalDays = customIntervalDays,
      nextDueDateIso = nextDueDate,
      isNextDueDateExplicit = isNextDueExplicit,
      finalDueDateIso = finalDueDate,
      lastPaidDateIso = lastPaidDate,
      documentDateIso = documentDateIso,
      totalRepaymentCents = totalRepayment,
      interestCents = interest,
      serviceFeeCents = serviceFee,
      processingFeeCents = processingFee,
      otherFeesCents = otherFees,
      loanStatus = loanStatus,
      isSchedulePartial = isSchedulePartial,
      scheduleVersion = if (adapterResult.isRestructuredOrFlexi) 2 else 1,
      scheduleEffectiveDateIso = scheduleEffectiveDateIso,
      obligationRole = adapterResult.obligationRole,
      installmentRows = extractedRows,
      textCorrections = textAudits,
      issues = issues,
      detectedLabelCues = labelCues,
      numericDateOrderUsed = effectiveNumericOrder,
      averageOcrConfidence = page.quality.averageConfidence
    )

    return FinancialReasoningEngine.enrichSinglePage(rawExtraction, page.quality)
  }

  private fun extractHeaderHeroPrincipal(
    rows: List<NormalizedOcrRow>,
    idx: Int
  ): ExtractedField<Long>? {
    for (row in rows.take(4)) {
      val firstCol = row.columns.firstOrNull()
        ?.replace(standaloneHeaderHashCodeRegex, "")
        ?.trim()
        .orEmpty()
      val heroMatch = Regex(
        """^(?:₱|PHP|Php)\s*([0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{2})?|[0-9]+\.[0-9]{2})\s+([A-Za-z][A-Za-z0-9\s\-]{2,40})$"""
      ).find(firstCol) ?: continue
      val titlePart = heroMatch.groupValues[2].trim()
      val titleLower = titlePart.lowercase(Locale.US)
      if (!containsAny(titleLower, "loan", "credit", "cash", "gives", "later", "installment", "order", "skyro", "tala", "atome", "billease", "maya", "gcash")) {
        continue
      }
      val cents = MoneyFormatter.parsePesoToCents(heroMatch.groupValues[1]) ?: continue
      if (cents > 0L) {
        return ExtractedField.extracted(
          value = cents,
          sourceSnippet = row.text,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Original loan amount read from header title in screenshot #${idx + 1}."
        )
      }
    }
    return null
  }

  private fun resolveInstallmentMultiplierForCell(
    rows: List<NormalizedOcrRow>,
    rowIdx: Int,
    cellIdx: Int,
    claimedValueCells: MutableSet<Pair<Int, Int>>
  ): Triple<Long, Int, String>? {
    val row = rows[rowIdx]
    val cells = row.cells.ifEmpty {
      listOf(OcrCell(row.text, row.left, row.top, row.right, row.bottom, row.averageConfidence))
    }
    val labelCell = cells[cellIdx]

    fun parseMultiplier(text: String): Pair<Long, Int>? {
      val m = installmentMultiplierRegex.find(text) ?: return null
      val repairedNum = OcrLayoutNormalizer.repairNumericAmountToken(m.groupValues[1])
      val cents = MoneyFormatter.parsePesoToCents(repairedNum) ?: return null
      val count = m.groupValues[2].toIntOrNull() ?: return null
      return if (cents > 0L && count in 1..120) cents to count else null
    }

    parseMultiplier(labelCell.text)?.let { (cents, count) ->
      return Triple(cents, count, labelCell.text)
    }

    for (rIdx in (cellIdx + 1) until cells.size) {
      val rightCell = cells[rIdx]
      parseMultiplier(rightCell.text)?.let { (cents, count) ->
        claimedValueCells.add(rowIdx to rIdx)
        return Triple(cents, count, "${labelCell.text} -> ${rightCell.text}")
      }
    }

    val belowRow = rows.getOrNull(rowIdx + 1)
    if (belowRow != null) {
      val belowCells = belowRow.cells.ifEmpty {
        listOf(OcrCell(belowRow.text, belowRow.left, belowRow.top, belowRow.right, belowRow.bottom, belowRow.averageConfidence))
      }
      val aligned = findHorizontallyAlignedCell(labelCell, belowCells)
      if (aligned != null) {
        val (bIdx, bCell) = aligned
        if ((rowIdx + 1 to bIdx) !in claimedValueCells) {
          parseMultiplier(bCell.text)?.let { (cents, count) ->
            claimedValueCells.add(rowIdx + 1 to bIdx)
            return Triple(cents, count, "${labelCell.text} -> ${bCell.text}")
          }
        }
      }
    }

    return null
  }

  private fun isInstallmentLabel(cellLower: String): Boolean {
    val norm = cellLower.trim().trimEnd(':', '-')
    return norm in setOf(
      "installment", "installments", "installment amount", "monthly installment",
      "weekly installment", "biweekly installment", "bi-weekly installment",
      "per installment", "scheduled installment"
    )
  }

  private fun classifyMonetaryLabelForCell(
    cellLower: String,
    isLikelyScheduleRow: Boolean,
    providerProfile: ProviderProfile?,
    productClassification: LoanProductClassification?,
    hasInstallmentContext: Boolean,
    learnedPrefs: LearnedScannerPreferences
  ): LoanFieldKey? {
    // 1. Learned user correction mapping takes top precedence (except when percentage-only)
    if (!containsAny(cellLower, "interest rate", "monthly rate", "eir", "apr")) {
      val cueLabel = extractLabelPortion(cellLower)
      if (cueLabel.isNotBlank()) {
        learnedPrefs.labelOverrides[cueLabel]?.let { learnedKey ->
          return learnedKey
        }
      }
    }

    // 2. Provider-specific field synonyms
    if (providerProfile != null) {
      for ((key, phrases) in providerProfile.fieldSynonyms) {
        if (phrases.any { phrase -> cellLower.contains(phrase) }) {
          if (key == LoanFieldKey.AMOUNT_CURRENTLY_DUE && containsAny(cellLower, "remaining balance", "total remaining")) {
            continue
          }
          return key
        }
      }
    }

    // 3. Universal & Product-Aware semantic label interpretation
    return when {
      // Credit Line specific fields (never conflate available credit with debt!)
      containsAny(cellLower, "total credit limit", "credit limit", "approved limit", "revolving limit") ->
        LoanFieldKey.CREDIT_LIMIT

      containsAny(cellLower, "available credit", "remaining credit", "spendable limit", "available limit") ->
        LoanFieldKey.AVAILABLE_CREDIT

      containsAny(cellLower, "used credit", "credit used", "utilized credit", "utilized limit") ->
        LoanFieldKey.USED_CREDIT

      containsAny(cellLower, "statement balance", "total statement balance", "billed balance", "total billed") ->
        LoanFieldKey.STATEMENT_BALANCE

      containsAny(cellLower, "minimum amount due", "minimum due", "min. amount due", "min due") ->
        LoanFieldKey.MINIMUM_DUE

      containsAny(cellLower, "past due amount", "past-due amount", "overdue amount", "amount overdue") ->
        LoanFieldKey.PAST_DUE_AMOUNT

      containsAny(cellLower, "unpaid charges", "late fee", "late charges", "penalty fee", "penalties") &&
        !containsAny(cellLower, "if paid late", "up to ", "when late") ->
        LoanFieldKey.UNPAID_CHARGES

      // Remaining Installment Total (MUST check before generic balance or installment!)
      containsAny(
        cellLower,
        "remaining installment total",
        "remaining installments total",
        "total remaining installments",
        "total unpaid installments",
        "remaining scheduled total",
        "unpaid installment total",
        "installments total left"
      ) || (isStandaloneRemainingLabel(cellLower) && hasInstallmentContext) ->
        LoanFieldKey.REMAINING_INSTALLMENT_TOTAL

      // Amount Currently Due (distinct from overall remaining balance!)
      containsAny(
        cellLower,
        "amount currently due",
        "currently due",
        "amount due now",
        "current amount due",
        "current due",
        "due this cycle",
        "amount to pay",
        "current bill",
        "this month bill",
        "this month due",
        "upcoming bill",
        "total bill due",
        "pay now amount"
      ) || (isStandaloneAmountDueLabel(cellLower) && !containsAny(cellLower, "total amount due back")) ->
        LoanFieldKey.AMOUNT_CURRENTLY_DUE

      // Total Amount Due (context-dependent: revolving credit statement vs full repayment)
      cellLower.contains("total amount due") -> {
        if (providerProfile?.singlePaymentCardHint == true ||
          productClassification == LoanProductClassification.REVOLVING_CREDIT ||
          productClassification == LoanProductClassification.BNPL_PAY_LATER
        ) {
          LoanFieldKey.STATEMENT_BALANCE
        } else {
          LoanFieldKey.TOTAL_REPAYMENT
        }
      }

      // Outstanding / Current Balance
      (containsAny(
        cellLower,
        "current outstanding balance",
        "outstanding balance",
        "remaining balance",
        "current balance",
        "principal balance",
        "outstanding principal",
        "unpaid balance",
        "loan balance",
        "balance remaining",
        "remaining principal",
        "remaining due",
        "contract balance",
        "early payoff",
        "payoff amount",
        "remaining unpaid",
        "outstanding unpaid"
      ) || isStandaloneBalanceLabel(cellLower) || (isStandaloneRemainingLabel(cellLower) && !hasInstallmentContext)) &&
        !containsAny(cellLower, "available balance", "wallet balance", "credit limit", "installment total") ->
        LoanFieldKey.OUTSTANDING_BALANCE

      // Original Loan Amount / Principal
      containsAny(
        cellLower,
        "original loan amount",
        "original amount",
        "loan amount",
        "principal amount",
        "amount borrowed",
        "financed amount",
        "credit availed",
        "borrowed amount",
        "amount availed",
        "order total",
        "purchase amount",
        "principal"
      ) && !containsAny(cellLower, "balance", "remaining") ->
        LoanFieldKey.ORIGINAL_PRINCIPAL

      // Disbursed Amount vs Net Amount Received
      containsAny(cellLower, "disbursed amount", "amount disbursed", "loan proceeds") ->
        LoanFieldKey.DISBURSED_AMOUNT

      containsAny(
        cellLower,
        "amount received", "net proceeds", "net disbursed",
        "cash received", "received amount", "credited to"
      ) -> LoanFieldKey.AMOUNT_RECEIVED

      // Total Repayment / Total Payable
      containsAny(
        cellLower,
        "total repayment", "total scheduled repayment", "total payable",
        "total amount payable", "total scheduled", "total loan cost", "total to repay", "repay total"
      ) -> LoanFieldKey.TOTAL_REPAYMENT

      // Individual Installment Amount
      !isLikelyScheduleRow && (containsAny(
        cellLower,
        "installment amount", "monthly installment", "weekly installment",
        "biweekly installment", "bi-weekly installment", "next installment",
        "installment due", "amortization", "per installment", "per payment",
        "each payment", "monthly due", "weekly due", "monthly payment",
        "weekly payment", "repayment amount", "scheduled installment"
      ) || isInstallmentLabel(cellLower)) -> LoanFieldKey.INSTALLMENT_AMOUNT

      // Interest (never match rates/percentages)
      containsAny(
        cellLower,
        "total interest", "interest fee", "interest amount", "interest charge", "interest"
      ) && !containsAny(cellLower, "interest rate", "monthly rate", "eir", "apr") ->
        LoanFieldKey.INTEREST

      // Service Fee
      containsAny(cellLower, "service fee", "service charge", "platform fee", "monthly admin fee") ->
        LoanFieldKey.SERVICE_FEE

      // Processing Fee
      containsAny(cellLower, "processing fee", "origination fee", "disbursement fee") ->
        LoanFieldKey.PROCESSING_FEE

      // Other Fees
      containsAny(
        cellLower,
        "other fee", "other fees", "admin fee",
        "convenience fee", "doc stamp", "documentary stamp", "dst", "insurance fee", "notarial fee"
      ) && !containsAny(cellLower, "if paid late", "up to ", "when late") ->
        LoanFieldKey.OTHER_FEES

      else -> null
    }
  }

  private fun resolveMoneyForCell(
    rows: List<NormalizedOcrRow>,
    rowIdx: Int,
    cellIdx: Int,
    claimedValueCells: MutableSet<Pair<Int, Int>>,
    providerProfile: ProviderProfile?,
    allowBare: Boolean
  ): Triple<MoneyMatch, String, ExtractionConfidence>? {
    val row = rows[rowIdx]
    val cells = row.cells.ifEmpty {
      listOf(OcrCell(row.text, row.left, row.top, row.right, row.bottom, row.averageConfidence))
    }
    val labelCell = cells[cellIdx]

    // 1. Inside the same cell (e.g. "Remaining Balance: ₱1,500.00")
    val sameCellMoney = extractBestMoneyFromCleanText(
      text = stripLeadingLabelFromCell(labelCell.text),
      allowBare = allowBare || labelCell.text.contains(':') || labelCell.text.contains('=')
    )
    if (sameCellMoney != null) {
      return Triple(sameCellMoney, labelCell.text, ExtractionConfidence.HIGH)
    }

    // 2. To the RIGHT on the same horizontal row, stopping before any next label cell
    for (rIdx in (cellIdx + 1) until cells.size) {
      val rightCell = cells[rIdx]
      val rightLower = canonicalizeKeywords(rightCell.text)
      if (looksLikeFinancialLabel(rightLower, providerProfile)) break
      if (containsNoisePhrase(rightLower, providerProfile, null)) break
      val m = extractBestMoneyFromCleanText(rightCell.text, allowBare = true)
      if (m != null) {
        claimedValueCells.add(rowIdx to rIdx)
        val conf = if (rIdx == cellIdx + 1) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM
        return Triple(m, "${labelCell.text} -> ${rightCell.text}", conf)
      }
    }

    // 3. Horizontally aligned cell DIRECTLY BELOW (supports 2-column metric grids & card stacks)
    for (offset in 1..2) {
      val targetRowIdx = rowIdx + offset
      val belowRow = rows.getOrNull(targetRowIdx) ?: break
      val belowCells = belowRow.cells.ifEmpty {
        listOf(OcrCell(belowRow.text, belowRow.left, belowRow.top, belowRow.right, belowRow.bottom, belowRow.averageConfidence))
      }
      if (offset == 1 && belowRow.columns.any { looksLikeFinancialLabel(canonicalizeKeywords(it), providerProfile) }) {
        val alignedBelow = findHorizontallyAlignedCell(labelCell, belowCells)
        if (alignedBelow != null) {
          val (bIdx, bCell) = alignedBelow
          if ((targetRowIdx to bIdx) !in claimedValueCells &&
            !looksLikeFinancialLabel(canonicalizeKeywords(bCell.text), providerProfile) &&
            !containsNoisePhrase(canonicalizeKeywords(bCell.text), providerProfile, null)
          ) {
            val m = extractBestMoneyFromCleanText(bCell.text, allowBare = true)
            if (m != null) {
              claimedValueCells.add(targetRowIdx to bIdx)
              return Triple(m, "${labelCell.text} -> ${bCell.text}", ExtractionConfidence.HIGH)
            }
          }
        }
        break
      }

      val alignedBelow = findHorizontallyAlignedCell(labelCell, belowCells)
      if (alignedBelow != null) {
        val (bIdx, bCell) = alignedBelow
        if ((targetRowIdx to bIdx) !in claimedValueCells &&
          !looksLikeFinancialLabel(canonicalizeKeywords(bCell.text), providerProfile) &&
          !containsNoisePhrase(canonicalizeKeywords(bCell.text), providerProfile, null)
        ) {
          val m = extractBestMoneyFromCleanText(bCell.text, allowBare = true)
          if (m != null) {
            claimedValueCells.add(targetRowIdx to bIdx)
            val conf = if (offset == 1) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM
            return Triple(m, "${labelCell.text} -> ${bCell.text}", conf)
          }
        }
      }
      if (belowRow.text.isNotBlank()) break
    }

    // 4. Hero numeric display DIRECTLY ABOVE the label
    if (rowIdx > 0) {
      val aboveRowIdx = rowIdx - 1
      val aboveRow = rows[aboveRowIdx]
      val aboveCells = aboveRow.cells.ifEmpty {
        listOf(OcrCell(aboveRow.text, aboveRow.left, aboveRow.top, aboveRow.right, aboveRow.bottom, aboveRow.averageConfidence))
      }
      val alignedAbove = findHorizontallyAlignedCell(labelCell, aboveCells)
      if (alignedAbove != null) {
        val (aIdx, aCell) = alignedAbove
        if ((aboveRowIdx to aIdx) !in claimedValueCells &&
          !looksLikeFinancialLabel(canonicalizeKeywords(aCell.text), providerProfile) &&
          !containsNoisePhrase(canonicalizeKeywords(aCell.text), providerProfile, null) &&
          FlexibleDateParser.parseSingleDate(aCell.text) == null
        ) {
          val m = extractBestMoneyFromCleanText(aCell.text, allowBare = false)
          if (m != null) {
            claimedValueCells.add(aboveRowIdx to aIdx)
            return Triple(m, "${aCell.text} -> ${labelCell.text}", ExtractionConfidence.MEDIUM)
          }
        }
      }
    }

    return null
  }

  private fun resolveIntegerForCell(
    rows: List<NormalizedOcrRow>,
    rowIdx: Int,
    cellIdx: Int,
    claimedValueCells: MutableSet<Pair<Int, Int>>
  ): Pair<Int, String>? {
    val row = rows[rowIdx]
    val cells = row.cells.ifEmpty {
      listOf(OcrCell(row.text, row.left, row.top, row.right, row.bottom, row.averageConfidence))
    }
    val labelCell = cells[cellIdx]

    val inlineInt = Regex(
      """[:\-=]\s*(\d{1,3})\b|\b(\d{1,3})\s*(?:months?|mos\.?|installments?|payments?|left)\b""",
      RegexOption.IGNORE_CASE
    ).find(labelCell.text)?.let { m ->
      m.groupValues[1].ifBlank { m.groupValues[2] }.toIntOrNull()
    }
    if (inlineInt != null) return inlineInt to labelCell.text

    for (rIdx in (cellIdx + 1) until cells.size) {
      val rightCell = cells[rIdx]
      if (looksLikeFinancialLabel(canonicalizeKeywords(rightCell.text), null)) break
      val num = Regex("""^\s*(\d{1,3})\s*(?:months?|mos\.?|installments?|payments?)?\s*$""", RegexOption.IGNORE_CASE)
        .find(rightCell.text)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,3})\b""").find(rightCell.text)?.groupValues?.get(1)?.toIntOrNull()
      if (num != null && !rightCell.text.contains('₱') && !rightCell.text.contains('.')) {
        claimedValueCells.add(rowIdx to rIdx)
        return num to "${labelCell.text} -> ${rightCell.text}"
      }
    }

    val belowRow = rows.getOrNull(rowIdx + 1)
    if (belowRow != null) {
      val belowCells = belowRow.cells.ifEmpty {
        listOf(OcrCell(belowRow.text, belowRow.left, belowRow.top, belowRow.right, belowRow.bottom, belowRow.averageConfidence))
      }
      val aligned = findHorizontallyAlignedCell(labelCell, belowCells)
      if (aligned != null) {
        val (bIdx, bCell) = aligned
        if ((rowIdx + 1 to bIdx) !in claimedValueCells &&
          !looksLikeFinancialLabel(canonicalizeKeywords(bCell.text), null) &&
          !bCell.text.contains('₱') &&
          !bCell.text.contains('%')
        ) {
          val num = Regex("""^\s*(\d{1,3})\s*(?:months?|mos\.?|installments?|payments?)?\s*$""", RegexOption.IGNORE_CASE)
            .find(bCell.text)?.groupValues?.get(1)?.toIntOrNull()
          if (num != null) {
            claimedValueCells.add(rowIdx + 1 to bIdx)
            return num to "${labelCell.text} -> ${bCell.text}"
          }
        }
      }
    }
    return null
  }

  private fun resolveDateForRowOrCells(
    rows: List<NormalizedOcrRow>,
    rowIdx: Int,
    cellIdx: Int,
    numericOrder: NumericDateOrder,
    contextYear: Int?,
    referenceDate: LocalDate
  ): Pair<ParsedDateMatch, String>? {
    val row = rows[rowIdx]
    val cells = row.cells.ifEmpty {
      listOf(OcrCell(row.text, row.left, row.top, row.right, row.bottom, row.averageConfidence))
    }
    val labelCell = cells[cellIdx]

    FlexibleDateParser.parseSingleDate(labelCell.text, numericOrder, contextYear, referenceDate)?.let {
      return it to labelCell.text
    }

    for (rIdx in (cellIdx + 1) until cells.size) {
      val rightCell = cells[rIdx]
      if (looksLikeFinancialLabel(canonicalizeKeywords(rightCell.text), null)) break
      FlexibleDateParser.parseSingleDate(rightCell.text, numericOrder, contextYear, referenceDate)?.let {
        return it to "${labelCell.text} -> ${rightCell.text}"
      }
    }

    val belowRow = rows.getOrNull(rowIdx + 1)
    if (belowRow != null) {
      val belowCells = belowRow.cells.ifEmpty {
        listOf(OcrCell(belowRow.text, belowRow.left, belowRow.top, belowRow.right, belowRow.bottom, belowRow.averageConfidence))
      }
      val aligned = findHorizontallyAlignedCell(labelCell, belowCells)
      if (aligned != null) {
        val bCell = aligned.second
        if (!looksLikeFinancialLabel(canonicalizeKeywords(bCell.text), null)) {
          FlexibleDateParser.parseSingleDate(bCell.text, numericOrder, contextYear, referenceDate)?.let {
            return it to "${labelCell.text} -> ${bCell.text}"
          }
        }
      }
    }

    if (rowIdx > 0) {
      val aboveRow = rows[rowIdx - 1]
      val aboveCells = aboveRow.cells.ifEmpty {
        listOf(OcrCell(aboveRow.text, aboveRow.left, aboveRow.top, aboveRow.right, aboveRow.bottom, aboveRow.averageConfidence))
      }
      val aligned = findHorizontallyAlignedCell(labelCell, aboveCells)
      if (aligned != null) {
        val aCell = aligned.second
        if (!looksLikeFinancialLabel(canonicalizeKeywords(aCell.text), null)) {
          FlexibleDateParser.parseSingleDate(aCell.text, numericOrder, contextYear, referenceDate)?.let {
            return it to "${aCell.text} -> ${labelCell.text}"
          }
        }
      }
    }

    return null
  }

  private fun findHorizontallyAlignedCell(
    anchor: OcrCell,
    candidates: List<OcrCell>
  ): Pair<Int, OcrCell>? {
    if (candidates.size == 1) {
      val single = candidates.single()
      val xOverlap = max(0, min(anchor.right, single.right) - max(anchor.left, single.left))
      val leftClose = abs(anchor.left - single.left) <= 220
      val centerClose = abs(anchor.centerX - single.centerX) <= 240
      return if (xOverlap > 0 || leftClose || centerClose) 0 to single else null
    }
    var bestIdx = -1
    var bestScore = Int.MAX_VALUE
    candidates.forEachIndexed { idx, cand ->
      val xOverlap = max(0, min(anchor.right, cand.right) - max(anchor.left, cand.left))
      val centerDist = abs(anchor.centerX - cand.centerX)
      val leftDist = abs(anchor.left - cand.left)
      val minDist = min(centerDist, leftDist)
      if (xOverlap > 0 || minDist <= 140) {
        val score = if (xOverlap > 0) -xOverlap else minDist
        if (score < bestScore) {
          bestScore = score
          bestIdx = idx
        }
      }
    }
    return if (bestIdx >= 0) bestIdx to candidates[bestIdx] else null
  }

  private fun extractLenderAndType(
    rows: List<NormalizedOcrRow>,
    idx: Int,
    providerProfile: ProviderProfile?,
    learnedPrefs: LearnedScannerPreferences
  ): Pair<ExtractedField<String>, ExtractedField<String>> {
    if (providerProfile != null) {
      val matchedRow = rows.firstOrNull { r ->
        providerProfile.detectPatterns.any { it.containsMatchIn(r.text) }
      }
      val snippet = matchedRow?.text ?: providerProfile.canonicalLender
      val lender = ExtractedField.extracted(
        value = providerProfile.canonicalLender,
        sourceSnippet = snippet,
        screenshotIndex = idx,
        confidence = ExtractionConfidence.HIGH,
        verificationReason = "Recognized ${providerProfile.canonicalLender} provider layout/branding."
      )
      val learnedCategory = learnedPrefs.providerCategoryDefaults[providerProfile.id]
      val categoryVal = learnedCategory ?: providerProfile.defaultLoanType
      val type = if (!categoryVal.isNullOrBlank()) {
        if (learnedCategory != null && learnedCategory != providerProfile.defaultLoanType) {
          ExtractedField.estimated(
            value = categoryVal,
            sourceSnippet = snippet,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Applied your preferred category for ${providerProfile.canonicalLender}."
          )
        } else {
          ExtractedField.extracted(
            value = categoryVal,
            sourceSnippet = snippet,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Default category for ${providerProfile.canonicalLender}."
          )
        }
      } else {
        ExtractedField.notDetected()
      }
      return lender to type
    }

    for (row in rows) {
      val lower = row.text.lowercase(Locale.US)
      if (containsAny(lower, "lender", "provider", "finance company", "bank name", "merchant", "platform")) {
        val right = extractRightSideText(row)
        if (!right.isNullOrBlank() && right.length in 2..40 && !right.all { it.isDigit() }) {
          return ExtractedField.extracted(
            value = right,
            sourceSnippet = row.text,
            screenshotIndex = idx,
            confidence = adjustConfidenceWithLineQuality(ExtractionConfidence.HIGH, row.averageConfidence),
            verificationReason = "Lender/provider name read next to explicit label in screenshot #${idx + 1}."
          ) to ExtractedField.notDetected()
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
          return ExtractedField.extracted(
            value = code,
            sourceSnippet = row.text,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Extracted loan/reference code in screenshot #${idx + 1}."
          )
        }
      }
      val hashMatch = inlineOrderHashRegex.find(row.text)
      if (hashMatch != null) {
        val code = hashMatch.groupValues[1].trim()
        if (code.length >= 3) {
          return ExtractedField.extracted(
            value = code,
            sourceSnippet = row.text,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Extracted order/loan # code in screenshot #${idx + 1}."
          )
        }
      }
    }
    // Check top header rows for bare "#46008372" reference badge
    for (row in rows.take(4)) {
      val bareHash = standaloneHeaderHashCodeRegex.find(row.text)
      if (bareHash != null) {
        val code = bareHash.groupValues[1].trim()
        if (code.length >= 5 && code.any { it.isDigit() }) {
          return ExtractedField.extracted(
            value = code,
            sourceSnippet = row.text,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Extracted reference # code from header in screenshot #${idx + 1}."
          )
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
          return ExtractedField.extracted(
            value = right,
            sourceSnippet = row.text,
            screenshotIndex = idx,
            confidence = ExtractionConfidence.HIGH,
            verificationReason = "Loan/item name read directly in screenshot #${idx + 1}."
          )
        }
      }
    }
    for (row in rows.take(4)) {
      val rawCol = row.columns.firstOrNull()?.trim().orEmpty()
      // Strip leading hero peso amount ("₱1,000.00 GCash Cash Loan" -> "GCash Cash Loan") and trailing #refId
      val cleaned = rawCol
        .replace(Regex("""^(?:₱|PHP|Php)\s*[0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{2})?\s+"""), "")
        .replace(standaloneHeaderHashCodeRegex, "")
        .trim()
      val lower = cleaned.lowercase(Locale.US)
      if (cleaned.length in 4..45 &&
        cleaned.any { it.isLetter() } &&
        !looksOnlyCurrencyOrDate(cleaned) &&
        !containsAny(lower, "balance", "payment schedule", "repayment schedule", "loan details", "installment plan", "summary", "transaction", "status", "details", "sent to") &&
        (detectedLender == null || !cleaned.equals(detectedLender, ignoreCase = true)) &&
        cleaned.contains(Regex("""\b(loan|order|installment|credit|cash|phone|laptop|tuition|bag|shoes|bill|gives|later)\b""", RegexOption.IGNORE_CASE))
      ) {
        val suffix = detectedRefId?.let { if (!cleaned.contains(it, ignoreCase = true)) " #$it" else "" } ?: ""
        return ExtractedField.extracted(
          value = (cleaned + suffix).take(50),
          sourceSnippet = row.text,
          screenshotIndex = idx,
          confidence = ExtractionConfidence.HIGH,
          verificationReason = "Read from header — confirm loan name."
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
    val cleanedText = percentageRegex.replace(text, " ")
    val lower = cleanedText.lowercase(Locale.US)

    remainingFractionRegex.find(cleanedText)?.let { m ->
      val rem = m.groupValues[1].toIntOrNull()
      val orig = m.groupValues[2].toIntOrNull()
      if (rem != null && orig != null && orig in 1..120 && rem in 0..orig) {
        return Triple(
          if (currentOrig.value == null) ExtractedField.extracted(orig, text, screenshotIndex, verificationReason = "Total count read from \"$text\".") else currentOrig,
          currentPaid,
          if (currentRem.value == null) ExtractedField.extracted(rem, text, screenshotIndex, verificationReason = "Remaining count read from \"$text\".") else currentRem
        )
      }
    }

    for (m in paidFractionRegex.findAll(cleanedText)) {
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
          if (currentOrig.value == null) ExtractedField.extracted(b, text, screenshotIndex, verificationReason = "Total count read from \"$text\".") else currentOrig,
          currentPaid,
          if (currentRem.value == null) ExtractedField.extracted(a, text, screenshotIndex, verificationReason = "Remaining count read from \"$text\".") else currentRem
        )
      } else {
        return Triple(
          if (currentOrig.value == null) ExtractedField.extracted(b, text, screenshotIndex, verificationReason = "Total count read from \"$text\".") else currentOrig,
          if (currentPaid.value == null) ExtractedField.extracted(a, text, screenshotIndex, verificationReason = "Paid count read from \"$text\".") else currentPaid,
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
          1 -> ExtractedField.extracted(RepaymentFrequency.DAILY, text, idx, verificationReason = "Explicitly labeled daily / every 1 day.") to null
          7 -> ExtractedField.extracted(RepaymentFrequency.WEEKLY, text, idx, verificationReason = "Explicitly labeled every 7 days.") to null
          14 -> ExtractedField.extracted(RepaymentFrequency.EVERY_14_DAYS, text, idx, verificationReason = "Explicitly labeled every 14 days.") to null
          else -> ExtractedField.extracted(RepaymentFrequency.CUSTOM_DAYS, text, idx, verificationReason = "Explicitly labeled every $days days.") to days
        }
      }
    }
    return when {
      Regex("""\bsemi[- ]?monthly\b|\btwice\s+(?:a|per)\s+month\b|\b15th\s*(?:&|and)\s*30th\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.TWICE_MONTHLY, text, idx, verificationReason = "Explicitly labeled twice-monthly / semi-monthly.") to null
      Regex("""\bquarterly\b|\bevery\s+3\s+months?\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.QUARTERLY, text, idx, verificationReason = "Explicitly labeled quarterly.") to null
      Regex("""\bdaily\b|\bevery\s+day\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.DAILY, text, idx, verificationReason = "Explicitly labeled daily.") to null
      Regex("""\bbi[- ]?weekly\b|\bevery\s+(?:two|2)\s+weeks\b|\bfortnightly\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.EVERY_14_DAYS, text, idx, verificationReason = "Explicitly labeled biweekly.") to null
      Regex("""\bweekly\b|\bevery\s+week\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.WEEKLY, text, idx, verificationReason = "Explicitly labeled weekly.") to null
      Regex("""\bmonthly\b|\bevery\s+month\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.MONTHLY, text, idx, verificationReason = "Explicitly labeled monthly.") to null
      Regex("""\bone[- ]?time\b|\bsingle\s+payment\b|\blump\s+sum\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.ONE_TIME, text, idx, verificationReason = "Explicitly labeled lump-sum / one-time payment.") to null
      Regex("""\birregular\b|\bcustom\s+schedule\b|\bflexible\s+dates?\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
        ExtractedField.extracted(RepaymentFrequency.IRREGULAR, text, idx, verificationReason = "Explicitly labeled irregular/custom schedule.") to null
      else -> null
    }
  }

  private fun extractScheduleRows(
    rows: List<NormalizedOcrRow>,
    numericOrder: NumericDateOrder,
    contextYear: Int?,
    referenceDate: LocalDate,
    screenshotIndex: Int,
    providerProfile: ProviderProfile?
  ): List<ExtractedInstallmentRow> {
    val result = mutableListOf<ExtractedInstallmentRow>()
    var i = 0
    while (i < rows.size) {
      val row = rows[i]
      val lower = canonicalizeKeywords(row.text)
      val dateMatch = FlexibleDateParser.parseSingleDate(row.text, numericOrder, contextYear, referenceDate)

      if (dateMatch != null) {
        val prevRowLower = rows.getOrNull(i - 1)?.text?.let { canonicalizeKeywords(it) }.orEmpty()
        val isBookingFollowupRow = containsAny(prevRowLower, "sent to", "credited to", "disbursed to", "loan date", "booking date")
        val isNonScheduleMetadata = isBookingFollowupRow ||
          nonScheduleDateHeaderKeywords.any { lower.contains(it) } ||
          containsAny(
            lower,
            "next payment due", "next due date", "next payment date", "next due:", "next due |",
            "due date:", "due date |", "payment due date", "due on:", "due on |",
            "final payment", "maturity date", "last paid", "last payment made",
            "statement date", "as of", "statement period", "billing period"
          )

        val nextRow = rows.getOrNull(i + 1)
        val nextRowDate = nextRow?.let {
          FlexibleDateParser.parseSingleDate(it.text, numericOrder, contextYear, referenceDate)
        }
        val nextRowUsableForCard = nextRow != null &&
          nextRowDate == null &&
          !looksLikeFinancialLabel(canonicalizeKeywords(nextRow.text), providerProfile)
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
          val rowWithoutDate = row.text
            .replace(dateMatch.rawText, " ")
            .replace(Regex("""^\s*(?:#?\d{1,2}(?:st|nd|rd|th)?\s*(?:\||\.|-|:)\s*|#?\d{1,2}\s+)""", RegexOption.IGNORE_CASE), " ")
          var moneyMatch = extractBestMoneyFromCleanText(rowWithoutDate, allowBare = true)
          var snippet = row.text
          var combinedStatusText = lower

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
            val dateConf = adjustConfidenceWithLineQuality(dateMatch.confidence, row.averageConfidence)
            val dueStatus = when {
              dateConf == ExtractionConfidence.HIGH -> DueDateStatus.ESTIMATED_REQUIRES_CONFIRMATION
              else -> DueDateStatus.UNCONFIRMED
            }
            result.add(
              ExtractedInstallmentRow(
                id = rowId,
                installmentNumberHint = numberHint,
                dueDateIso = dateMatch.isoDate,
                rawDateText = dateMatch.rawText,
                dateStatus = dueStatus,
                dateConfidence = dateConf,
                amountCents = moneyMatch?.cents,
                status = status,
                sourceSnippet = snippet,
                screenshotIndex = screenshotIndex,
                verificationNote = listOfNotNull(dateMatch.note, moneyMatch?.note).joinToString("; ").ifBlank { null }
              )
            )
          }
        }
      } else if (hasInstallmentOrdinal(row.text) && !looksLikeFinancialLabel(lower, providerProfile)) {
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
              dateConfidence = ExtractionConfidence.MEDIUM,
              amountCents = money.cents,
              status = parseInstallmentStatus(lower),
              sourceSnippet = row.text,
              screenshotIndex = screenshotIndex,
              verificationNote = "Due date missing in screenshot — confirm before relying on schedule."
            )
          )
        }
      }
      i++
    }

    return result.distinctBy { Triple(it.dueDateIso ?: it.rawDateText, it.amountCents, it.installmentNumberHint) }
  }

  internal data class MoneyMatch(
    val cents: Long,
    val rawText: String,
    val confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
    val note: String? = null
  )

  internal fun extractBestMoneyFromCleanText(text: String, allowBare: Boolean): MoneyMatch? {
    val cleaned = percentageRegex.replace(text, " ")
    for (m in prefixedPesoRegex.findAll(cleaned)) {
      val hadTypoPrefix = Regex("""^(?:F|£|Ph\.?|P\.)\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(
        m.value.substring(0, m.value.indexOf(m.groupValues[1])).trim()
      )
      parsePesoTokenSafely(m.groupValues[1], hasCurrencyPrefix = true, glyphNormalized = hadTypoPrefix)?.let { return it }
    }
    if (allowBare) {
      val stripped = paidFractionRegex.replace(cleaned, " ")
      for (m in bareDecimalMoneyRegex.findAll(stripped)) {
        parsePesoTokenSafely(m.groupValues[1], hasCurrencyPrefix = false, glyphNormalized = false)?.let { return it }
      }
      for (m in bareLabeledMoneyRegex.findAll(stripped)) {
        val token = m.groupValues[1]
        val intVal = token.toLongOrNull()
        if (intVal != null && intVal in 2020L..2035L && !token.contains('.') && !token.contains(',')) continue
        parsePesoTokenSafely(token, hasCurrencyPrefix = false, glyphNormalized = false)?.let { return it }
      }
    }
    return null
  }

  private fun parsePesoTokenSafely(
    rawToken: String,
    hasCurrencyPrefix: Boolean,
    glyphNormalized: Boolean
  ): MoneyMatch? {
    val repairedLetters = rawToken
      .replace('O', '0')
      .replace('o', '0')
      .replace('l', '1')
      .replace('I', '1')
      .replace(Regex("""\s+"""), "")
    val hadOcrRepair = repairedLetters != rawToken || glyphNormalized

    if (!repairedLetters.contains('.') && repairedLetters.count { it == ',' } == 1) {
      val commaParts = repairedLetters.split(',')
      if (commaParts.size == 2 && commaParts[1].length == 2) {
        val decimalStr = "${commaParts[0]}.${commaParts[1]}"
        MoneyFormatter.parsePesoToCents(decimalStr)?.let { cents ->
          return MoneyMatch(
            cents = cents,
            rawText = rawToken,
            confidence = ExtractionConfidence.MEDIUM,
            note = "Interpreted comma decimal in \"$rawToken\" — verify amount."
          )
        }
      }
    }

    val dotParts = repairedLetters.split('.')
    if (dotParts.size >= 3 && dotParts.last().length == 2) {
      val repairedDots = dotParts.dropLast(1).joinToString(",") + "." + dotParts.last()
      MoneyFormatter.parsePesoToCents(repairedDots)?.let { cents ->
        return MoneyMatch(
          cents = cents,
          rawText = rawToken,
          confidence = ExtractionConfidence.MEDIUM,
          note = "Normalized OCR punctuation in \"$rawToken\" — verify amount."
        )
      }
    }

    val cents = MoneyFormatter.parsePesoToCents(repairedLetters) ?: return null
    val conf = when {
      hadOcrRepair -> ExtractionConfidence.MEDIUM
      hasCurrencyPrefix || repairedLetters.contains('.') || repairedLetters.contains(',') -> ExtractionConfidence.HIGH
      else -> ExtractionConfidence.MEDIUM
    }
    val note = if (hadOcrRepair) {
      "Normalized OCR glyph in \"$rawToken\" — verify amount."
    } else null
    return MoneyMatch(cents = cents, rawText = rawToken, confidence = conf, note = note)
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

  private fun stripLeadingLabelFromCell(cellText: String): String {
    val colonIdx = cellText.indexOf(':')
    if (colonIdx in 1 until cellText.lastIndex) {
      return cellText.substring(colonIdx + 1).trim()
    }
    val eqIdx = cellText.indexOf('=')
    if (eqIdx in 1 until cellText.lastIndex) {
      return cellText.substring(eqIdx + 1).trim()
    }
    return cellText
  }

  private fun extractLabelPortion(cellLower: String): String {
    val cutColon = cellLower.indexOf(':')
    val base = if (cutColon > 0) cellLower.substring(0, cutColon) else cellLower
    return base
      .replace(prefixedPesoRegex, " ")
      .replace(Regex("""[₱\d,./\-|:]+"""), " ")
      .replace(Regex("""\s+"""), " ")
      .trim()
  }

  private fun isStandaloneBalanceLabel(cellLower: String): Boolean {
    val normalized = cellLower.trim().trimEnd(':', '-')
    return normalized == "balance" || normalized == "total balance"
  }

  private fun isStandaloneRemainingLabel(cellLower: String): Boolean {
    val normalized = cellLower.trim().trimEnd(':', '-')
    return normalized == "remaining" || normalized == "remaining amount"
  }

  private fun isStandaloneAmountDueLabel(cellLower: String): Boolean {
    val normalized = cellLower.trim().trimEnd(':', '-')
    return normalized == "amount due" || normalized == "due now"
  }

  private fun containsNoisePhrase(
    lower: String,
    providerProfile: ProviderProfile?,
    productClassification: LoanProductClassification?
  ): Boolean {
    if (universalNoisePhrases.any { lower.contains(it) }) return true
    // On non-revolving fixed installment loans, generic "credit limit" / "available credit" banners are noise;
    // on revolving credit lines, they are extracted into CREDIT_LIMIT / AVAILABLE_CREDIT!
    if (productClassification != LoanProductClassification.REVOLVING_CREDIT &&
      productClassification != LoanProductClassification.BNPL_PAY_LATER &&
      containsAny(lower, "available limit", "credit limit", "remaining credit limit", "spendable limit")
    ) {
      return true
    }
    return providerProfile?.extraNoisePhrases?.any { lower.contains(it) } == true
  }

  private fun looksLikeFinancialLabel(lower: String, providerProfile: ProviderProfile?): Boolean {
    if (containsAny(
        lower,
        "balance", "principal", "loan amount", "installment", "repayment",
        "interest", "service fee", "processing", "other fee", "amount received",
        "net proceeds", "next due", "next payment", "final payment", "maturity",
        "lender", "tenor", "term", "status", "amount due", "minimum due", "current bill",
        "total payable", "amount to pay", "total due", "credit limit", "available credit",
        "used credit", "statement balance", "past due", "unpaid charges"
      ) || isStandaloneRemainingLabel(lower)
    ) return true
    if (providerProfile != null) {
      return providerProfile.fieldSynonyms.values.flatten().any { lower.contains(it) }
    }
    return false
  }

  private fun looksLikeScheduleRow(
    row: NormalizedOcrRow,
    numericOrder: NumericDateOrder,
    contextYear: Int?,
    referenceDate: LocalDate,
    providerProfile: ProviderProfile?
  ): Boolean {
    val lower = canonicalizeKeywords(row.text)
    val hasDate = FlexibleDateParser.parseSingleDate(row.text, numericOrder, contextYear, referenceDate) != null
    val hasOrdinal = hasInstallmentOrdinal(row.text)
    val hasMoney = extractBestMoneyFromCleanText(row.text, allowBare = false) != null
    val hasNonSchedLabel = containsAny(
      lower,
      "balance", "principal", "next due", "next payment", "final payment",
      "statement date", "as of", "loan date", "booking date", "disbursed", "sent to"
    )
    return !hasNonSchedLabel && (
      (hasDate && (hasMoney || hasExplicitScheduleStatus(lower) || hasOrdinal)) ||
        (hasOrdinal && hasMoney && !looksLikeFinancialLabel(lower, providerProfile))
      )
  }

  private val scheduleStatusWordRegex = Regex(
    """\b(?:paid|settled|completed|upcoming|unpaid|overdue|past\s+due|scheduled|pending|partially\s+paid|partial|refunded|reversed|disputed)\b|(?<!next\s)(?<!amount\s)(?<!total\s)(?<!current\s)(?<!minimum\s)\bdue\b(?!\s*(?:date|on|now|this)\b)""",
    RegexOption.IGNORE_CASE
  )

  private fun hasExplicitScheduleStatus(lower: String): Boolean =
    scheduleStatusWordRegex.containsMatchIn(lower)

  private fun parseInstallmentStatus(lower: String): ExtractedInstallmentStatus = when {
    containsAny(lower, "refunded", "refund") -> ExtractedInstallmentStatus.REFUNDED
    containsAny(lower, "reversed", "reversal", "cancelled", "canceled", "voided") -> ExtractedInstallmentStatus.REVERSED
    containsAny(lower, "disputed", "dispute", "under review") -> ExtractedInstallmentStatus.DISPUTED
    containsAny(lower, "partially paid", "partial payment", "partial") -> ExtractedInstallmentStatus.PARTIALLY_PAID
    containsAny(lower, "unpaid", "not paid") -> ExtractedInstallmentStatus.UNPAID
    containsAny(lower, "overdue", "past due", "missed") -> ExtractedInstallmentStatus.OVERDUE
    containsAny(lower, "pending", "processing") -> ExtractedInstallmentStatus.PENDING
    containsAny(lower, "paid", "settled", "completed") -> ExtractedInstallmentStatus.PAID
    containsAny(lower, "upcoming", "due", "scheduled", "next") -> ExtractedInstallmentStatus.UPCOMING
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
      .replace("ba1ance", "balance")
      .replace("balamce", "balance")
      .replace("princ1pal", "principal")
      .replace("prlncipal", "principal")

  private fun adjustConfidenceWithLineQuality(
    base: ExtractionConfidence,
    lineConfidence: Float?
  ): ExtractionConfidence {
    if (lineConfidence == null) return base
    return when {
      lineConfidence < 0.45f -> ExtractionConfidence.LOW
      lineConfidence < 0.68f && base == ExtractionConfidence.HIGH -> ExtractionConfidence.MEDIUM
      else -> base
    }
  }

  private fun minConfidence(a: ExtractionConfidence, b: ExtractionConfidence): ExtractionConfidence =
    if (a.rank >= b.rank) a else b

  private fun containsAny(text: String, vararg keywords: String): Boolean =
    keywords.any { text.contains(it) }
}
