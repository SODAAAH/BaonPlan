package com.baonplan.finance.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.baonplan.finance.domain.ocr.OcrDocumentPage
import com.baonplan.finance.domain.ocr.OcrPageQuality
import com.baonplan.finance.domain.ocr.PdfEmbeddedTextExtractor
import com.baonplan.finance.domain.ocr.PdfTextExtractionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ImportedPdfStagingInfo(
  val tempFile: File,
  val pageCount: Int,
  val hasSelectableText: Boolean
)

/**
 * 100% on-device offline PDF reader combining selectable text stream extraction with
 * Android PdfRenderer + ML Kit OCR fallback for scanned / image-only PDF pages.
 * Never logs PDF paths, text contents, or financial details.
 */
object PdfDocumentProcessor {

  private const val MAX_RENDER_DPI_SCALE = 2.5f
  private const val MAX_RENDER_LONG_EDGE_PX = 2400
  private const val THUMB_MAX_EDGE_PX = 420
  private const val MAX_PAGES_PER_PDF = 25

  suspend fun importPdfUriToPrivateTempFile(
    context: Context,
    uri: Uri
  ): Result<ImportedPdfStagingInfo> = withContext(Dispatchers.IO) {
    runCatching {
      val destFile = File(
        ImageScanPreprocessor.tempDir(context),
        "staged_pdf_${System.currentTimeMillis()}_${System.nanoTime()}.pdf"
      )
      try {
        context.contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(destFile).use { output ->
            input.copyTo(output)
          }
        } ?: error("Unable to open selected PDF document.")

        if (!destFile.exists() || destFile.length() <= 0L) {
          error("Selected PDF file is empty or unreadable.")
        }

        val bytes = destFile.readBytes()
        when (val textOutcome = PdfEmbeddedTextExtractor.extractFromBytes(bytes)) {
          is PdfTextExtractionOutcome.PasswordProtected -> {
            error(textOutcome.userMessage)
          }
          is PdfTextExtractionOutcome.CorruptedOrUnsupported -> {
            error(textOutcome.userMessage)
          }
          is PdfTextExtractionOutcome.Success -> {
            val rendererPageCount = inspectRendererPageCount(destFile)
              .getOrElse { err ->
                if (err is SecurityException) {
                  error("This PDF is password-protected. Unlock or export an unencrypted PDF, or upload screenshots.")
                }
                textOutcome.totalPages
              }
            val effectivePages = maxOf(textOutcome.totalPages, rendererPageCount).coerceAtLeast(1)
            val anySelectable = textOutcome.pages.any { it.hasSelectableText }
            ImportedPdfStagingInfo(
              tempFile = destFile,
              pageCount = effectivePages,
              hasSelectableText = anySelectable
            )
          }
        }
      } catch (t: Throwable) {
        ImageScanPreprocessor.deleteTempFile(destFile)
        throw t
      }
    }
  }

  suspend fun renderPdfPageThumbnail(
    pdfFile: File,
    pageIndex: Int = 0
  ): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
      renderPdfPageToBitmap(pdfFile, pageIndex, THUMB_MAX_EDGE_PX)
    }.getOrNull()
  }

  /**
   * Reads all pages of [pdfFile] (up to [MAX_PAGES_PER_PDF]) into [OcrDocumentPage] entries,
   * using embedded selectable text where present and on-device PdfRenderer + ML Kit OCR
   * automatically on scanned / image-only pages.
   */
  suspend fun extractPagesFromPdfFile(
    pdfFile: File,
    startScreenshotIndex: Int,
    sourceDocumentId: String,
    documentTitle: String
  ): Result<List<OcrDocumentPage>> = withContext(Dispatchers.Default) {
    runCatching {
      if (!pdfFile.exists() || !pdfFile.isFile || pdfFile.length() <= 0L) {
        error("PDF document could not be found in temporary scan cache.")
      }

      val bytes = withContext(Dispatchers.IO) { pdfFile.readBytes() }
      val textOutcome = PdfEmbeddedTextExtractor.extractFromBytes(bytes)
      val embeddedByPage = when (textOutcome) {
        is PdfTextExtractionOutcome.PasswordProtected -> error(textOutcome.userMessage)
        is PdfTextExtractionOutcome.CorruptedOrUnsupported -> error(textOutcome.userMessage)
        is PdfTextExtractionOutcome.Success -> textOutcome.pages.associateBy { it.pageNumber }
      }

      val rendererCount = withContext(Dispatchers.IO) {
        inspectRendererPageCount(pdfFile).getOrNull()
      }
      val totalPages = maxOf(
        embeddedByPage.keys.maxOrNull() ?: 0,
        rendererCount ?: 0
      ).coerceIn(1, MAX_PAGES_PER_PDF)

      val results = mutableListOf<OcrDocumentPage>()
      for (pageIdx in 0 until totalPages) {
        val pageNum = pageIdx + 1
        val syntheticIndex = startScreenshotIndex + pageIdx
        val embedded = embeddedByPage[pageNum]

        if (embedded != null && embedded.hasSelectableText && embedded.lines.isNotEmpty()) {
          results.add(
            OcrDocumentPage(
              screenshotIndex = syntheticIndex,
              width = 1240,
              height = 1754,
              lines = embedded.lines,
              rawFullText = embedded.rawText,
              quality = OcrPageQuality(
                averageConfidence = 0.99f,
                lowConfidenceLineCount = 0,
                usedContrastEnhancement = false,
                stripCount = 1,
                detectedSkewDegrees = 0f
              ),
              sourceDocumentId = sourceDocumentId,
              documentTitle = documentTitle,
              pageNumber = pageNum,
              totalPagesInDocument = totalPages,
              isPdfPage = true,
              isScannedPdfPage = false
            )
          )
        } else {
          // Image-only or scanned PDF page -> render via PdfRenderer + run offline ML Kit OCR
          val renderedBitmap = withContext(Dispatchers.IO) {
            renderPdfPageToBitmap(pdfFile, pageIdx, MAX_RENDER_LONG_EDGE_PX)
          }
          if (renderedBitmap != null) {
            val ocrPage = MlKitDebtOcrDataSource.recognizeBitmap(
              screenshotIndex = syntheticIndex,
              bitmap = renderedBitmap
            ).getOrNull()
            if (ocrPage != null) {
              results.add(
                ocrPage.copy(
                  sourceDocumentId = sourceDocumentId,
                  documentTitle = documentTitle,
                  pageNumber = pageNum,
                  totalPagesInDocument = totalPages,
                  isPdfPage = true,
                  isScannedPdfPage = true
                )
              )
            }
          }
        }
      }

      if (results.isEmpty()) {
        error("Could not read text from $documentTitle. Ensure the PDF is clear and unencrypted, or upload screenshots.")
      }
      results
    }
  }

  private fun inspectRendererPageCount(pdfFile: File): Result<Int> = runCatching {
    ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
      PdfRenderer(pfd).use { renderer ->
        renderer.pageCount
      }
    }
  }

  private fun renderPdfPageToBitmap(
    pdfFile: File,
    pageIndex: Int,
    maxLongEdgePx: Int
  ): Bitmap? {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null
    return try {
      pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
      renderer = PdfRenderer(pfd)
      if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
      page = renderer.openPage(pageIndex)

      val rawLong = maxOf(page.width, page.height).coerceAtLeast(1)
      val scale = (maxLongEdgePx.toFloat() / rawLong.toFloat()).coerceIn(0.5f, MAX_RENDER_DPI_SCALE)
      val targetW = (page.width * scale).toInt().coerceAtLeast(1)
      val targetH = (page.height * scale).toInt().coerceAtLeast(1)

      val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
      Canvas(bitmap).drawColor(Color.WHITE)
      page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      bitmap
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { page?.close() }
      runCatching { renderer?.close() }
      runCatching { pfd?.close() }
    }
  }
}
