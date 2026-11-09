package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.model.DebtStatus
import com.baonplan.finance.domain.model.DueDateStatus
import com.baonplan.finance.domain.model.RepaymentFrequency

enum class FieldOrigin(val badgeLabel: String) {
  EXTRACTED("From screenshot"),
  APP_CALCULATED("Calculated by app"),
  NOT_DETECTED("Not detected")
}

enum class ExtractionConfidence {
  HIGH,
  NEEDS_VERIFICATION,
  CONFLICT
}

data class ExtractedField<T>(
  val value: T? = null,
  val origin: FieldOrigin = FieldOrigin.NOT_DETECTED,
  val confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
  val sourceSnippet: String? = null,
  val sourceScreenshotIndex: Int? = null,
  val verificationReason: String? = null
) {
  companion object {
    fun <T> notDetected(): ExtractedField<T> = ExtractedField(
      value = null,
      origin = FieldOrigin.NOT_DETECTED,
      confidence = ExtractionConfidence.HIGH
    )

    fun <T> extracted(
      value: T,
      sourceSnippet: String? = null,
      screenshotIndex: Int? = null,
      confidence: ExtractionConfidence = ExtractionConfidence.HIGH,
      verificationReason: String? = null
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.EXTRACTED,
      confidence = confidence,
      sourceSnippet = sourceSnippet,
      sourceScreenshotIndex = screenshotIndex,
      verificationReason = verificationReason
    )

    fun <T> calculated(
      value: T,
      sourceSnippet: String? = null,
      screenshotIndex: Int? = null,
      confidence: ExtractionConfidence = ExtractionConfidence.NEEDS_VERIFICATION,
      verificationReason: String? = null
    ): ExtractedField<T> = ExtractedField(
      value = value,
      origin = FieldOrigin.APP_CALCULATED,
      confidence = confidence,
      sourceSnippet = sourceSnippet,
      sourceScreenshotIndex = screenshotIndex,
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
  val centerY: Int get() = top + height / 2
}

data class OcrLine(
  val text: String,
  val box: OcrBoundingBox? = null
)

data class OcrDocumentPage(
  val screenshotIndex: Int,
  val width: Int = 0,
  val height: Int = 0,
  val lines: List<OcrLine>,
  val rawFullText: String = lines.joinToString("\n") { it.text }
)

enum class ExtractedInstallmentStatus(val label: String) {
  PAID("Paid"),
  UPCOMING("Upcoming"),
  OVERDUE("Overdue"),
  UNKNOWN("Status unclear")
}

data class ExtractedInstallmentRow(
  val id: String,
  val installmentNumberHint: Int? = null,
  val dueDateIso: String? = null,
  val rawDateText: String = "",
  val dateStatus: DueDateStatus = DueDateStatus.UNCONFIRMED,
  val dateConfidence: ExtractionConfidence = ExtractionConfidence.NEEDS_VERIFICATION,
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
  val lender: ExtractedField<String> = ExtractedField.notDetected(),
  val loanName: ExtractedField<String> = ExtractedField.notDetected(),
  val loanType: ExtractedField<String> = ExtractedField.notDetected(),
  val loanReferenceId: ExtractedField<String> = ExtractedField.notDetected(),
  val originalPrincipalCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val amountReceivedCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val outstandingBalanceCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val remainingInstallmentTotalCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val installmentAmountCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val originalInstallmentCount: ExtractedField<Int> = ExtractedField.notDetected(),
  val paidInstallmentsCount: ExtractedField<Int> = ExtractedField.notDetected(),
  val remainingInstallmentsCount: ExtractedField<Int> = ExtractedField.notDetected(),
  val frequency: ExtractedField<RepaymentFrequency> = ExtractedField.notDetected(),
  val customIntervalDays: ExtractedField<Int> = ExtractedField.notDetected(),
  val nextDueDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val finalDueDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val lastPaidDateIso: ExtractedField<String> = ExtractedField.notDetected(),
  val totalRepaymentCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val interestCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val serviceFeeCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val processingFeeCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val otherFeesCents: ExtractedField<Long> = ExtractedField.notDetected(),
  val loanStatus: ExtractedField<DebtStatus> = ExtractedField.notDetected(),
  val installmentRows: List<ExtractedInstallmentRow> = emptyList(),
  val issues: List<ImportValidationIssue> = emptyList()
)

data class DebtImportDraft(
  val candidateId: String,
  val sourceScreenshotIndices: List<Int>,
  val lender: ExtractedField<String>,
  val loanName: ExtractedField<String>,
  val loanType: ExtractedField<String>,
  val loanReferenceId: ExtractedField<String>,
  val originalPrincipalCents: ExtractedField<Long>,
  val amountReceivedCents: ExtractedField<Long>,
  val outstandingBalanceCents: ExtractedField<Long>,
  val remainingInstallmentTotalCents: ExtractedField<Long>,
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
  val installmentRows: List<ExtractedInstallmentRow>,
  val mergeNotes: List<String> = emptyList(),
  val validationIssues: List<ImportValidationIssue> = emptyList(),
  val duplicateMatches: List<DuplicateDebtMatch> = emptyList()
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
