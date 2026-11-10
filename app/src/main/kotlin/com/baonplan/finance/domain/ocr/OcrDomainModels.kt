package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.LoanProductClassification
import com.baonplan.finance.domain.model.ObligationRole
import com.baonplan.finance.domain.model.RepaymentFrequency
import kotlinx.serialization.Serializable

enum class FieldOrigin(val badgeLabel: String) {
  EXTRACTED("Extracted"),
  CALCULATED("Calculated"),
  ESTIMATED("Estimated"),
  MANUALLY_CORRECTED("Manually corrected"),
  NEEDS_REVIEW("Needs review"),
  NOT_DETECTED("Not detected")
}

enum class ExtractionConfidence(val userLabel: String, val rank: Int) {
  HIGH("High confidence", 0),
  MEDIUM("Highlight for review", 1),
  LOW("Verify required", 2)
}

@Serializable
enum class LoanFieldKey(val displayName: String) {
  ORIGINAL_PRINCIPAL("Original loan amount"),
  DISBURSED_AMOUNT("Disbursed amount"),
  AMOUNT_RECEIVED("Net amount received"),
  OUTSTANDING_BALANCE("Outstanding balance"),
  OUTSTANDING_PRINCIPAL("Outstanding principal"),
  AMOUNT_CURRENTLY_DUE("Amount currently due"),
  REMAINING_INSTALLMENT_TOTAL("Remaining installment total"),
  PAST_DUE_AMOUNT("Past-due amount"),
  UNPAID_CHARGES("Unpaid charges"),
  CREDIT_LIMIT("Credit limit"),
  AVAILABLE_CREDIT("Available credit"),
  USED_CREDIT("Used credit"),
  STATEMENT_BALANCE("Statement balance"),
  MINIMUM_DUE("Minimum amount due"),
  STATEMENT_DATE("Statement date"),
  LOAN_BOOKING_DATE("Loan booking date"),
  INSTALLMENT_AMOUNT("Installment amount"),
  TOTAL_REPAYMENT("Total repayment"),
  INTEREST("Interest"),
  SERVICE_FEE("Service fee"),
  PROCESSING_FEE("Processing fee"),
  OTHER_FEES("Other fees"),
  ORIGINAL_COUNT("Total installments"),
  PAID_COUNT("Paid installments"),
  REMAINING_COUNT("Remaining installments"),
  NEXT_DUE_DATE("Next payment date"),
  FINAL_DUE_DATE("Final payment date"),
  LAST_PAID_DATE("Last paid date"),
  FREQUENCY("Repayment frequency"),
  LOAN_TYPE("Loan type"),
  PRODUCT_CLASSIFICATION("Product classification")
}

@Serializable
data class LearnedScannerPreferences(
  val labelOverrides: Map<String, LoanFieldKey> = emptyMap(),
  val providerDateOrder: Map<String, String> = emptyMap(),
  val providerCategoryDefaults: Map<String, String> = emptyMap(),
  val correctionCount: Int = 0
)

data class DetectedLabelValueCue(
  val normalizedLabel: String,
  val assignedField: LoanFieldKey?,
  val moneyCents: Long? = null,
  val intValue: Int? = null,
  val screenshotIndex: Int = 0
)

data class FieldAlternative<T>(
  val value: T,
  val label: String,
  val origin: FieldOrigin,
  val screenshotIndex: Int? = null,
  val sourceSnippet: String? = null
)

data class ExtractedField<T>(
  val value: T? = null,
  val origin: FieldOrigin = FieldOrigin.NOT_DETECTED,
  val confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
  val sourceSnippet: String? = null,
  val sourceScreenshotIndex: Int? = null,
  val verificationReason: String? = null,
  val hasConflict: Boolean = false,
  val alternatives: List<FieldAlternative<T>> = emptyList()
) {
  companion object {
    fun <T> notDetected(reason: String? = null): ExtractedField<T> = ExtractedField(
      value = null,
      origin = FieldOrigin.NOT_DETECTED,
      confidence = ExtractionConfidence.HIGH,
      verificationReason = reason
    )

    fun <T> extracted(
      value: T,
      sourceSnippet: String? = null,
      screenshotIndex: Int? = null,
      confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
      verificationReason: String? = null,
      hasConflict: Boolean = false,
      alternatives: List<FieldAlternative<T>> = emptyList()
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.EXTRACTED,
      confidence = confidence,
      sourceSnippet = sourceSnippet,
      sourceScreenshotIndex = screenshotIndex,
      verificationReason = verificationReason,
      hasConflict = hasConflict,
      alternatives = alternatives
    )

    fun <T> calculated(
      value: T,
      sourceSnippet: String? = null,
      screenshotIndex: Int? = null,
      confidence: ExtractionConfidence = ExtractionConfidence.MEDIUM,
      verificationReason: String? = null,
      hasConflict: Boolean = false,
      alternatives: List<FieldAlternative<T>> = emptyList()
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.CALCULATED,
      confidence = confidence,
      sourceSnippet = sourceSnippet,
      sourceScreenshotIndex = screenshotIndex,
      verificationReason = verificationReason,
      hasConflict = hasConflict,
      alternatives = alternatives
    )

    fun <T> estimated(
      value: T,
      sourceSnippet: String? = null,
      screenshotIndex: Int? = null,
      confidence: ExtractionConfidence = ExtractionConfidence.MEDIUM,
      verificationReason: String? = null,
      hasConflict: Boolean = false,
      alternatives: List<FieldAlternative<T>> = emptyList()
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.ESTIMATED,
      confidence = confidence,
      sourceSnippet = sourceSnippet,
      sourceScreenshotIndex = screenshotIndex,
      verificationReason = verificationReason,
      hasConflict = hasConflict,
      alternatives = alternatives
    )

    fun <T> needsReview(
      value: T,
      sourceSnippet: String? = null,
      screenshotIndex: Int? = null,
      verificationReason: String? = null,
      hasConflict: Boolean = false,
      alternatives: List<FieldAlternative<T>> = emptyList()
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.NEEDS_REVIEW,
      confidence = ExtractionConfidence.LOW,
      sourceSnippet = sourceSnippet,
      sourceScreenshotIndex = screenshotIndex,
      verificationReason = verificationReason,
      hasConflict = hasConflict,
      alternatives = alternatives
    )

    fun <T> manuallyCorrected(
      value: T,
      verificationReason: String = "Edited by user during scan review"
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.MANUALLY_CORRECTED,
      confidence = ExtractionConfidence.HIGH,
      verificationReason = verificationReason
    )
  }
}

data class OcrBoundingBox(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int
) {
  val width: Int get() = (right - left).coerceAtLeast(0)
  val height: Int get() = (bottom - top).coerceAtLeast(0)
  val centerX: Int get() = left + width / 2
  val centerY: Int get() = top + height / 2
}

data class OcrElement(
  val text: String,
  val box: OcrBoundingBox? = null,
  val confidence: Float? = null,
  val angle: Float? = null
)

data class OcrLine(
  val text: String,
  val box: OcrBoundingBox? = null,
  val confidence: Float? = null,
  val angle: Float? = null,
  val blockId: Int? = null,
  val elements: List<OcrElement> = emptyList()
)

data class OcrPageQuality(
  val averageConfidence: Float? = null,
  val lowConfidenceLineCount: Int = 0,
  val usedContrastEnhancement: Boolean = false,
  val stripCount: Int = 1,
  val detectedSkewDegrees: Float = 0f
)

data class OcrDocumentPage(
  val screenshotIndex: Int,
  val width: Int = 0,
  val height: Int = 0,
  val lines: List<OcrLine>,
  val rawFullText: String = lines.joinToString("\n") { it.text },
  val quality: OcrPageQuality = OcrPageQuality()
)

enum class ExtractedInstallmentStatus(val label: String) {
  PAID("Paid"),
  UPCOMING("Upcoming"),
  UNPAID("Unpaid"),
  PENDING("Pending"),
  PARTIALLY_PAID("Partially paid"),
  OVERDUE("Overdue"),
  REFUNDED("Refunded"),
  REVERSED("Reversed"),
  DISPUTED("Disputed"),
  UNKNOWN("Status unclear");

  val isSettledPaid: Boolean
    get() = this == PAID

  val isUnpaidObligation: Boolean
    get() = this == UPCOMING ||
      this == UNPAID ||
      this == PENDING ||
      this == PARTIALLY_PAID ||
      this == OVERDUE ||
      this == UNKNOWN

  val isCancelledOrReversed: Boolean
    get() = this == REFUNDED || this == REVERSED
}

data class TextCorrectionAudit(
  val rawText: String,
  val normalizedText: String,
  val reason: String,
  val screenshotIndex: Int = 0
)

data class ExtractedInstallmentRow(
  val id: String,
  val installmentNumberHint: Int? = null,
  val dueDateIso: String? = null,
  val rawDateText: String = "",
  val dateStatus: DueDateStatus = DueDateStatus.UNCONFIRMED,
  val dateConfidence: ExtractionConfidence = ExtractionConfidence.MEDIUM,
  val amountCents: Long? = null,
  val status: ExtractedInstallmentStatus = ExtractedInstallmentStatus.UPCOMING,
  val sourceSnippet: String = "",
  val screenshotIndex: Int = 0,
  val verificationNote: String? = null
)

enum class ExtractedDocumentKind {
  COMPLETE_DETAILS,
  SUMMARY_ONLY,
  SCHEDULE_ONLY,
  MIXED_DETAILS_AND_SCHEDULE,
  UNSTRUCTURED
}

enum class ImportSeverity {
  ERROR,
  WARNING,
  INFO
}

data class ImportValidationIssue(
  val fieldKey: String,
  val severity: ImportSeverity,
  val title: String,
  val message: String
)

enum class DuplicateConfidence(val label: String) {
  HIGH("Very likely duplicate"),
  POSSIBLE("Possible match")
}

data class DuplicateDebtMatch(
  val existingDebtId: Long,
  val debtName: String,
  val lender: String,
  val remainingBalanceCents: Long,
  val nextDueDate: String?,
  val confidence: DuplicateConfidence,
  val reasons: List<String>
)

data class SingleScreenshotExtraction(
  val screenshotIndex: Int,
  val documentKind: ExtractedDocumentKind,
  val providerProfileId: String? = null,
  val productClassification: ExtractedField<LoanProductClassification> = ExtractedField.notDetected(),
  val applicationProvider: ExtractedField<String> = ExtractedField.notDetected(),
  val actualCreditor: ExtractedField<String> = ExtractedField.notDetected(),
  val lender: ExtractedField<String> = ExtractedField.notDetected(),
  val loanName: ExtractedField<String> = ExtractedField.notDetected(),
  val loanType: ExtractedField<String> = ExtractedField.notDetected(),
  val loanReferenceId: ExtractedField<String> = ExtractedField.notDetected(),
  val loanBookingDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val originalPrincipalCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val disbursedAmountCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val amountReceivedCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val outstandingBalanceCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val amountCurrentlyDueCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val remainingInstallmentTotalCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val pastDueAmountCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val unpaidChargesCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val creditLimitCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val availableCreditCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val usedCreditCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val statementBalanceCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val minimumDueCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val statementDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val statementPeriod: ExtractedField<String> = ExtractedField.notDetected(),
  val installmentAmountCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val originalInstallmentCount: ExtractedField<Int> = ExtractedField.notDetected(),
  val paidInstallmentsCount: ExtractedField<Int> = ExtractedField.notDetected(),
  val remainingInstallmentsCount: ExtractedField<Int> = ExtractedField.notDetected(),
  val frequency: ExtractedField<RepaymentFrequency> = ExtractedField.notDetected(),
  val customIntervalDays: ExtractedField<Int> = ExtractedField.notDetected(),
  val nextDueDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val isNextDueDateExplicit: Boolean = false,
  val finalDueDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val lastPaidDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val documentDateIso: String? = null,
  val totalRepaymentCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val interestCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val serviceFeeCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val processingFeeCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val otherFeesCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val loanStatus: ExtractedField<DebtStatus> = ExtractedField.notDetected(),
  val isSchedulePartial: Boolean = false,
  val scheduleVersion: Int = 1,
  val scheduleEffectiveDateIso: String? = null,
  val obligationRole: ObligationRole = ObligationRole.STANDALONE,
  val installmentRows: List<ExtractedInstallmentRow> = emptyList(),
  val textCorrections: List<TextCorrectionAudit> = emptyList(),
  val issues: List<ImportValidationIssue> = emptyList(),
  val detectedLabelCues: List<DetectedLabelValueCue> = emptyList(),
  val numericDateOrderUsed: NumericDateOrder = NumericDateOrder.AMBIGUOUS,
  val averageOcrConfidence: Float? = null
)

data class DebtImportDraft(
  val candidateId: String,
  val sourceScreenshotIndices: List<Int>,
  val providerProfileId: String? = null,
  val productClassification: ExtractedField<LoanProductClassification> = ExtractedField.notDetected(),
  val applicationProvider: ExtractedField<String> = ExtractedField.notDetected(),
  val actualCreditor: ExtractedField<String> = ExtractedField.notDetected(),
  val lender: ExtractedField<String>,
  val loanName: ExtractedField<String>,
  val loanType: ExtractedField<String>,
  val loanReferenceId: ExtractedField<String>,
  val loanBookingDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val originalPrincipalCents: ExtractedField<Long>,
  val disbursedAmountCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val amountReceivedCents: ExtractedField<Long>,
  val outstandingBalanceCents: ExtractedField<Long>,
  val amountCurrentlyDueCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val remainingInstallmentTotalCents: ExtractedField<Long>,
  val pastDueAmountCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val unpaidChargesCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val creditLimitCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val availableCreditCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val usedCreditCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val statementBalanceCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val minimumDueCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val statementDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val statementPeriod: ExtractedField<String> = ExtractedField.notDetected(),
  val calculatedUnpaidScheduleSumCents: ExtractedField<Long>,
  val calculatedPaidScheduleSumCents: ExtractedField<Long>,
  val installmentAmountCents: ExtractedField<Long>,
  val originalInstallmentCount: ExtractedField<Int>,
  val paidInstallmentsCount: ExtractedField<Int>,
  val remainingInstallmentsCount: ExtractedField<Int>,
  val frequency: ExtractedField<RepaymentFrequency>,
  val customIntervalDays: ExtractedField<Int>,
  val nextDueDateIso: ExtractedField<String>,
  val finalDueDateIso: ExtractedField<String>,
  val lastPaidDateIso: ExtractedField<String>,
  val totalRepaymentCents: ExtractedField<Long>,
  val interestCents: ExtractedField<Long>,
  val serviceFeeCents: ExtractedField<Long>,
  val processingFeeCents: ExtractedField<Long>,
  val otherFeesCents: ExtractedField<Long>,
  val loanStatus: ExtractedField<DebtStatus>,
  val dueDateStatus: DueDateStatus,
  val isSchedulePartial: Boolean = false,
  val scheduleVersion: Int = 1,
  val scheduleEffectiveDateIso: String? = null,
  val obligationRole: ObligationRole = ObligationRole.STANDALONE,
  val suggestedParentDebtId: Long? = null,
  val installmentRows: List<ExtractedInstallmentRow>,
  val textCorrections: List<TextCorrectionAudit> = emptyList(),
  val mergeNotes: List<String> = emptyList(),
  val validationIssues: List<ImportValidationIssue> = emptyList(),
  val duplicateMatches: List<DuplicateDebtMatch> = emptyList(),
  val detectedLabelCues: List<DetectedLabelValueCue> = emptyList(),
  val numericDateOrderUsed: NumericDateOrder = NumericDateOrder.AMBIGUOUS
)

sealed interface ScanAnalysisOutcome {
  data class SingleLoan(val draft: DebtImportDraft) : ScanAnalysisOutcome
  data class MultipleLoans(
    val drafts: List<DebtImportDraft>,
    val explanation: String
  ) : ScanAnalysisOutcome
  data class Failed(
    val userMessage: String,
    val failedScreenshotIndices: List<Int> = emptyList()
  ) : ScanAnalysisOutcome
}
