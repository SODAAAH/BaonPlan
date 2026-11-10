package com.baonplan.finance.data.ocr

import com.baonplan.finance.data.local.entity.DebtEntity
import com.baonplan.finance.data.local.entity.InstallmentEntity
import com.baonplan.finance.domain.ocr.DebtDuplicateDetector
import com.baonplan.finance.domain.ocr.DebtImportValidator
import com.baonplan.finance.domain.ocr.DebtOcrExtractor
import com.baonplan.finance.domain.ocr.LearnedScannerPreferences
import com.baonplan.finance.domain.ocr.MultiScreenshotDebtMerger
import com.baonplan.finance.domain.ocr.OcrDocumentPage
import com.baonplan.finance.domain.ocr.ScanAnalysisOutcome
import com.baonplan.finance.domain.ocr.SingleScreenshotExtraction
import com.baonplan.finance.domain.util.ManilaTime
import java.io.File
import java.time.LocalDate

data class StagedScanInput(
  val screenshotIndex: Int,
  val tempFile: File,
  val rotationDegrees: Int,
  val displayLabel: String,
  val isPdf: Boolean = false,
  val documentId: String = "doc_$screenshotIndex"
)

data class ScanCoordinatorReport(
  val outcome: ScanAnalysisOutcome,
  val partialImageWarnings: List<String> = emptyList(),
  val extractedPages: List<SingleScreenshotExtraction> = emptyList()
)

/**
 * Coordinates Stage A (adaptive image preprocessing + strip slicing), Stage B (offline ML Kit OCR
 * with session caching and orientation/contrast retry), and Stage C (financial interpretation,
 * reasoning, multi-screenshot linking/grouping, validation, and duplicate detection).
 * Never logs OCR text, file paths, or financial values.
 */
object DebtScanCoordinator {

  private const val MAX_CACHE_ENTRIES = 12
  private const val LOW_CONFIDENCE_RETRY_THRESHOLD = 0.68f

  private val ocrPageCache = object : LinkedHashMap<String, OcrDocumentPage>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OcrDocumentPage>?): Boolean =
      size > MAX_CACHE_ENTRIES
  }

  private val moneyHintRegex = Regex("""₱|\b(?:php|bal|due|loan|total)\b|\d[,\d]*\.\d{2}""", RegexOption.IGNORE_CASE)
  private val dateHintRegex = Regex("""\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b|\d{1,2}[/\-.]\d{1,2}""", RegexOption.IGNORE_CASE)

  fun clearSessionCache() {
    synchronized(ocrPageCache) {
      ocrPageCache.clear()
    }
  }

  suspend fun analyze(
    inputs: List<StagedScanInput>,
    existingDebts: List<DebtEntity>,
    existingInstallments: List<InstallmentEntity>,
    referenceDate: LocalDate = ManilaTime.today(),
    learnedPrefs: LearnedScannerPreferences = LearnedScannerPreferences()
  ): ScanCoordinatorReport {
    if (inputs.isEmpty()) {
      return ScanCoordinatorReport(
        outcome = ScanAnalysisOutcome.Failed("Select or capture at least one screenshot to scan.")
      )
    }

    val perPageExtractions = mutableListOf<SingleScreenshotExtraction>()
    val perPageFailures = mutableListOf<Pair<String, String>>()
    var nextSyntheticIndex = 0

    for (item in inputs) {
      if (item.isPdf) {
        val pdfPagesResult = PdfDocumentProcessor.extractPagesFromPdfFile(
          pdfFile = item.tempFile,
          startScreenshotIndex = nextSyntheticIndex,
          sourceDocumentId = item.documentId,
          documentTitle = item.displayLabel
        )
        val pdfPages = pdfPagesResult.getOrNull()
        if (pdfPages.isNullOrEmpty()) {
          perPageFailures.add(
            item.displayLabel to (pdfPagesResult.exceptionOrNull()?.message
              ?: "Couldn't read pages from ${item.displayLabel}.")
          )
          continue
        }
        nextSyntheticIndex += pdfPages.size
        for (page in pdfPages) {
          val extraction = DebtOcrExtractor.extract(
            page = page,
            referenceDate = referenceDate,
            learnedPrefs = learnedPrefs
          )
          perPageExtractions.add(extraction)
        }
      } else {
        val syntheticIdx = nextSyntheticIndex++
        val ocrResult = recognizePageWithAdaptiveRetry(item.copy(screenshotIndex = syntheticIdx))
        val page = ocrResult.getOrNull()?.copy(
          sourceDocumentId = item.documentId,
          documentTitle = item.displayLabel
        )
        if (page == null) {
          perPageFailures.add(
            item.displayLabel to (ocrResult.exceptionOrNull()?.message
              ?: "Couldn't read text from ${item.displayLabel}.")
          )
          continue
        }

        val extraction = DebtOcrExtractor.extract(
          page = page,
          referenceDate = referenceDate,
          learnedPrefs = learnedPrefs
        )
        perPageExtractions.add(extraction)
      }
    }

    if (perPageExtractions.isEmpty()) {
      val combinedError = perPageFailures.firstOrNull()?.second
        ?: "Couldn't extract readable loan text from the selected document(s) or screenshot(s)."
      return ScanCoordinatorReport(
        outcome = ScanAnalysisOutcome.Failed(
          userMessage = combinedError,
          failedScreenshotIndices = perPageFailures.indices.toList()
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

    val partialWarnings = perPageFailures.map { (label, msg) ->
      "$label: $msg"
    }

    return ScanCoordinatorReport(
      outcome = outcome,
      partialImageWarnings = partialWarnings,
      extractedPages = perPageExtractions
    )
  }

  fun remergeAuto(
    pages: List<SingleScreenshotExtraction>,
    existingDebts: List<DebtEntity>,
    existingInstallments: List<InstallmentEntity>
  ): ScanAnalysisOutcome = MultiScreenshotDebtMerger.merge(
    pages = pages,
    existingValidator = { draft -> DebtImportValidator.validateDraft(draft) },
    duplicateMatcher = { draft ->
      DebtDuplicateDetector.findPotentialDuplicates(
        draft = draft,
        existingDebts = existingDebts,
        existingInstallments = existingInstallments
      )
    }
  )

  fun remergeWithUserGroups(
    pages: List<SingleScreenshotExtraction>,
    groupedIndices: List<List<Int>>,
    existingDebts: List<DebtEntity>,
    existingInstallments: List<InstallmentEntity>
  ): ScanAnalysisOutcome = MultiScreenshotDebtMerger.mergeWithCustomGroups(
    pages = pages,
    groupedScreenshotIndices = groupedIndices,
    existingValidator = { draft -> DebtImportValidator.validateDraft(draft) },
    duplicateMatcher = { draft ->
      DebtDuplicateDetector.findPotentialDuplicates(
        draft = draft,
        existingDebts = existingDebts,
        existingInstallments = existingInstallments
      )
    }
  )

  private suspend fun recognizePageWithAdaptiveRetry(item: StagedScanInput): Result<OcrDocumentPage> {
    val cacheKey = "${item.tempFile.absolutePath}|${item.tempFile.lastModified()}|${item.tempFile.length()}|${item.rotationDegrees}"
    synchronized(ocrPageCache) {
      ocrPageCache[cacheKey]
    }?.let { cached ->
      return Result.success(cached.copy(screenshotIndex = item.screenshotIndex))
    }

    val cleanResult = runCleanOcr(item.screenshotIndex, item.tempFile, item.rotationDegrees)
    val cleanPage = cleanResult.getOrNull()

    var bestPage: OcrDocumentPage? = cleanPage
    var bestScore = cleanPage?.let { scorePageUsability(it) } ?: -1f
    var lastFailure = cleanResult.exceptionOrNull()

    // Retry with gentle monochrome contrast boost ONLY when initial crisp OCR looks weak
    if (cleanPage == null || pageNeedsContrastRetry(cleanPage)) {
      val contrastResult = runContrastOcrOnStrips(item.screenshotIndex, item.tempFile, item.rotationDegrees)
      val contrastPage = contrastResult.getOrNull()
      if (contrastPage != null) {
        val contrastScore = scorePageUsability(contrastPage)
        if (bestPage == null || contrastScore > bestScore + 3f) {
          bestPage = contrastPage
          bestScore = contrastScore
        }
      } else if (lastFailure == null) {
        lastFailure = contrastResult.exceptionOrNull()
      }
    }

    // Automatic orientation recovery if upright clean + contrast produced unusable output and user didn't rotate
    if ((bestPage == null || bestScore < 12f) && item.rotationDegrees == 0) {
      for (candidateRot in listOf(90, 270)) {
        val rotResult = runCleanOcr(item.screenshotIndex, item.tempFile, candidateRot)
        val rotPage = rotResult.getOrNull() ?: continue
        val rotAlnum = rotPage.rawFullText.count { it.isLetterOrDigit() }
        val rotScore = scorePageUsability(rotPage)
        val hasFinancialCue = moneyHintRegex.containsMatchIn(rotPage.rawFullText) ||
          dateHintRegex.containsMatchIn(rotPage.rawFullText)
        if (rotAlnum >= 20 && hasFinancialCue && rotScore > bestScore + 10f) {
          bestPage = rotPage
          bestScore = rotScore
        }
      }
    }

    if (bestPage != null) {
      synchronized(ocrPageCache) {
        ocrPageCache[cacheKey] = bestPage
      }
      return Result.success(bestPage)
    }

    return Result.failure(
      lastFailure ?: IllegalStateException(
        "Could not read text from ${item.displayLabel} (image may be corrupted or unsupported)."
      )
    )
  }

  private suspend fun runCleanOcr(
    screenshotIndex: Int,
    file: File,
    rotationDegrees: Int
  ): Result<OcrDocumentPage> {
    val strips = ImageScanPreprocessor.decodeBitmapStripsForOcr(
      file = file,
      rotationDegrees = rotationDegrees
    ).getOrElse { return Result.failure(it) }
    return MlKitDebtOcrDataSource.recognizeBitmapStrips(
      screenshotIndex = screenshotIndex,
      strips = strips,
      usedContrastEnhancement = false
    )
  }

  private suspend fun runContrastOcrOnStrips(
    screenshotIndex: Int,
    file: File,
    rotationDegrees: Int
  ): Result<OcrDocumentPage> {
    val rawStrips = ImageScanPreprocessor.decodeBitmapStripsForOcr(
      file = file,
      rotationDegrees = rotationDegrees
    ).getOrElse { return Result.failure(it) }

    val enhancedStrips = mutableListOf<OcrBitmapStrip>()
    try {
      for (strip in rawStrips) {
        val enhancedBitmap = try {
          ImageScanPreprocessor.createContrastEnhancedCopy(strip.bitmap)
        } finally {
          if (!strip.bitmap.isRecycled) strip.bitmap.recycle()
        }
        enhancedStrips.add(strip.copy(bitmap = enhancedBitmap))
      }
    } catch (t: Throwable) {
      rawStrips.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
      enhancedStrips.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
      return Result.failure(t)
    }

    return MlKitDebtOcrDataSource.recognizeBitmapStrips(
      screenshotIndex = screenshotIndex,
      strips = enhancedStrips,
      usedContrastEnhancement = true
    )
  }

  private fun pageNeedsContrastRetry(page: OcrDocumentPage): Boolean {
    val alnum = page.rawFullText.count { it.isLetterOrDigit() }
    if (alnum < 25) return true
    val avgConf = page.quality.averageConfidence
    if (avgConf != null &&
      avgConf < LOW_CONFIDENCE_RETRY_THRESHOLD &&
      page.lines.isNotEmpty() &&
      page.quality.lowConfidenceLineCount > page.lines.size / 2
    ) {
      return true
    }
    val hasMoneyOrDate = moneyHintRegex.containsMatchIn(page.rawFullText) ||
      dateHintRegex.containsMatchIn(page.rawFullText)
    return !hasMoneyOrDate && page.lines.size < 5
  }

  private fun scorePageUsability(page: OcrDocumentPage): Float {
    val text = page.rawFullText
    val alnum = text.count { it.isLetterOrDigit() }.coerceAtMost(350).toFloat() * 0.1f
    val lineBonus = page.lines.size.coerceAtMost(40).toFloat() * 0.5f
    val confBonus = (page.quality.averageConfidence ?: 0.72f) * 20f
    val moneyBonus = if (moneyHintRegex.containsMatchIn(text)) 12f else 0f
    val dateBonus = if (dateHintRegex.containsMatchIn(text)) 8f else 0f
    return alnum + lineBonus + confBonus + moneyBonus + dateBonus
  }
}
