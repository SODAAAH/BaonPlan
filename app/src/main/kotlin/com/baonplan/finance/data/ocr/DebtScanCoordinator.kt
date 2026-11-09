package com.baonplan.finance.data.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.ocr.DebtDuplicateDetector
import com.baonplan.finance.domain.ocr.DebtImportValidator
import com.baonplan.finance.domain.ocr.DebtOcrExtractor
import com.baonplan.finance.domain.ocr.MultiScreenshotDebtMerger
import com.baonplan.finance.domain.ocr.ScanAnalysisOutcome
import com.baonplan.finance.domain.ocr.SingleScreenshotExtraction
import com.baonplan.finance.domain.util.ManilaTime
import java.io.File
import java.time.LocalDate

data class StagedScanInput(
  val screenshotIndex: Int,
  val tempFile: File,
  val rotationDegrees: Int,
  val displayLabel: String
)

data class ScanCoordinatorReport(
  val outcome: ScanAnalysisOutcome,
  val partialImageWarnings: List<String> = emptyList()
)

/**
 * Coordinates image preprocessing, offline ML Kit text extraction, structured loan parsing,
 * multi-screenshot loan merging/splitting, validation, and duplicate detection.
 */
object DebtScanCoordinator {

  suspend fun analyze(
    inputs: List<StagedScanInput>,
    existingDebts: List<DebtEntity>,
    existingInstallments: List<InstallmentEntity>,
    referenceDate: LocalDate = ManilaTime.today()
  ): ScanCoordinatorReport {
    if (inputs.isEmpty()) {
      return ScanCoordinatorReport(
        outcome = ScanAnalysisOutcome.Failed("Select or capture at least one screenshot to scan.")
      )
    }

    val perPageExtractions = mutableListOf<SingleScreenshotExtraction>()
    val perPageFailures = mutableMapOf<Int, String>()

    for (item in inputs) {
      val bitmapResult = ImageScanPreprocessor.decodeBitmapForOcr(
        file = item.tempFile,
        rotationDegrees = item.rotationDegrees
      )
      val bitmap = bitmapResult.getOrNull()
      if (bitmap == null) {
        perPageFailures[item.screenshotIndex] =
          "Could not decode ${item.displayLabel} (image may be corrupted or unsupported)."
        continue
      }

      val ocrResult = MlKitDebtOcrDataSource.recognizeBitmap(item.screenshotIndex, bitmap)
      val page = ocrResult.getOrNull()
      if (page == null) {
        perPageFailures[item.screenshotIndex] = ocrResult.exceptionOrNull()?.message
          ?: "Couldn't read text from ${item.displayLabel}."
        continue
      }

      val extraction = DebtOcrExtractor.extract(page, referenceDate)
      perPageExtractions.add(extraction)
    }

    if (perPageExtractions.isEmpty()) {
      val combinedError = perPageFailures.values.firstOrNull()
        ?: "Couldn't extract readable loan text from the selected screenshot(s)."
      return ScanCoordinatorReport(
        outcome = ScanAnalysisOutcome.Failed(
          userMessage = combinedError,
          failedScreenshotIndices = perPageFailures.keys.toList()
        )
      )
    }

    val outcome = MultiScreenshotDebtMerger.merge(
      pages = perPageExtractions,
      existingValidator = { draft -> DebtImportValidator.validateDraft(draft) },
      duplicateMatcher = { draft ->
        DebtDuplicateDetector.findPotentialDuplicates(
          draft = draft,
          existingDebts = existingDebts,
          existingInstallments = existingInstallments
        )
      }
    )

    val partialWarnings = perPageFailures.entries.sortedBy { it.key }.map { (idx, msg) ->
      "Image #${idx + 1}: $msg"
    }

    return ScanCoordinatorReport(
      outcome = outcome,
      partialImageWarnings = partialWarnings
    )
  }
}
