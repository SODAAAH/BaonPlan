package com.baonplan.finance.data.ocr

import android.graphics.Bitmap
import com.baonplan.finance.domain.ocr.OcrBoundingBox
import com.baonplan.finance.domain.ocr.OcrDocumentPage
import com.baonplan.finance.domain.ocr.OcrElement
import com.baonplan.finance.domain.ocr.OcrLine
import com.baonplan.finance.domain.ocr.OcrPageQuality
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 100% on-device offline ML Kit Latin Text Recognition wrapper.
 * Captures block grouping, line/element bounding boxes, confidence, and skew angle.
 * Stitches tall-screenshot strips seamlessly, recycles input bitmaps immediately,
 * and logs zero OCR content or file URIs.
 */
object MlKitDebtOcrDataSource {

  private const val MIN_READABLE_ALPHANUMERIC_CHARS = 5
  private const val LOW_CONFIDENCE_THRESHOLD = 0.72f

  suspend fun recognizeBitmap(
    screenshotIndex: Int,
    bitmap: Bitmap
  ): Result<OcrDocumentPage> = recognizeBitmapStrips(
    screenshotIndex = screenshotIndex,
    strips = listOf(
      OcrBitmapStrip(
        bitmap = bitmap,
        yOffsetPx = 0,
        fullWidthPx = bitmap.width,
        fullHeightPx = bitmap.height
      )
    ),
    usedContrastEnhancement = false
  )

  suspend fun recognizeBitmapStrips(
    screenshotIndex: Int,
    strips: List<OcrBitmapStrip>,
    usedContrastEnhancement: Boolean = false
  ): Result<OcrDocumentPage> = withContext(Dispatchers.Default) {
    if (strips.isEmpty()) {
      return@withContext Result.failure(
        IllegalStateException("Screenshot #${screenshotIndex + 1} had no image data to scan.")
      )
    }

    var recognizer: TextRecognizer? = null
    val allLines = mutableListOf<OcrLine>()
    var blockIdOffset = 0
    val fullW = strips.first().fullWidthPx
    val fullH = strips.first().fullHeightPx

    try {
      recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

      for (strip in strips) {
        val bitmap = strip.bitmap
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = awaitTextTask(recognizer, inputImage)

        visionText.textBlocks.forEachIndexed { localBlockIdx, block ->
          val globalBlockId = blockIdOffset + localBlockIdx
          for (line in block.lines) {
            val rect = line.boundingBox
            val shiftedBox = rect?.let {
              OcrBoundingBox(
                left = it.left,
                top = it.top + strip.yOffsetPx,
                right = it.right,
                bottom = it.bottom + strip.yOffsetPx
              )
            }
            val lineConf = line.confidence.takeIf { it.isFinite() && it in 0f..1f }
            val lineAngle = line.angle.takeIf { it.isFinite() }
            val elements = line.elements.map { el ->
              val elRect = el.boundingBox
              OcrElement(
                text = el.text,
                box = elRect?.let {
                  OcrBoundingBox(
                    left = it.left,
                    top = it.top + strip.yOffsetPx,
                    right = it.right,
                    bottom = it.bottom + strip.yOffsetPx
                  )
                },
                confidence = el.confidence.takeIf { it.isFinite() && it in 0f..1f },
                angle = el.angle.takeIf { it.isFinite() }
              )
            }

            allLines.add(
              OcrLine(
                text = line.text,
                box = shiftedBox,
                confidence = lineConf,
                angle = lineAngle,
                blockId = globalBlockId,
                elements = elements
              )
            )
          }
        }
        blockIdOffset += visionText.textBlocks.size
      }

      val deduplicatedLines = if (strips.size > 1) {
        deduplicateStripSeamLines(allLines)
      } else {
        allLines
      }

      val rawText = deduplicatedLines.joinToString("\n") { it.text }
      val alnumCount = rawText.count { it.isLetterOrDigit() }

      if (deduplicatedLines.isEmpty() || alnumCount < MIN_READABLE_ALPHANUMERIC_CHARS) {
        Result.failure(
          IllegalStateException(
            "Couldn't read text from screenshot #${screenshotIndex + 1}. Try rotating it, choosing a clearer image, or entering details manually."
          )
        )
      } else {
        val confidences = deduplicatedLines.mapNotNull { it.confidence }
        val avgConf = if (confidences.isNotEmpty()) {
          (confidences.sum() / confidences.size.toFloat()).coerceIn(0f, 1f)
        } else null
        val lowConfCount = confidences.count { it < LOW_CONFIDENCE_THRESHOLD }
        val angles = deduplicatedLines.mapNotNull { it.angle }.map { abs(it) }.sorted()
        val medianSkew = if (angles.isNotEmpty()) angles[angles.size / 2] else 0f

        Result.success(
          OcrDocumentPage(
            screenshotIndex = screenshotIndex,
            width = fullW,
            height = fullH,
            lines = deduplicatedLines,
            rawFullText = rawText,
            quality = OcrPageQuality(
              averageConfidence = avgConf,
              lowConfidenceLineCount = lowConfCount,
              usedContrastEnhancement = usedContrastEnhancement,
              stripCount = strips.size,
              detectedSkewDegrees = medianSkew
            )
          )
        )
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Throwable) {
      Result.failure(
        IllegalStateException(
          "Text recognition couldn't read screenshot #${screenshotIndex + 1}. Please try again or enter details manually."
        )
      )
    } finally {
      runCatching { recognizer?.close() }
      strips.forEach { strip ->
        if (!strip.bitmap.isRecycled) {
          strip.bitmap.recycle()
        }
      }
    }
  }

  private fun deduplicateStripSeamLines(lines: List<OcrLine>): List<OcrLine> {
    val kept = mutableListOf<OcrLine>()
    for (candidate in lines) {
      val candBox = candidate.box
      val candNorm = normalizeKey(candidate.text)
      val dupIndex = kept.indexOfFirst { existing ->
        val exBox = existing.box
        if (candBox == null || exBox == null) {
          candNorm == normalizeKey(existing.text)
        } else {
          val yCenterClose = abs(candBox.centerY - exBox.centerY) <= maxOf(candBox.height, exBox.height) * 0.65f
          val xOverlap = max(0, min(candBox.right, exBox.right) - max(candBox.left, exBox.left))
          val narrowerW = min(candBox.width, exBox.width).coerceAtLeast(1)
          val xOverlapRatio = xOverlap.toFloat() / narrowerW.toFloat()
          val exNorm = normalizeKey(existing.text)
          val sameOrSubstring = candNorm == exNorm ||
            (candNorm.length >= 6 && exNorm.contains(candNorm)) ||
            (exNorm.length >= 6 && candNorm.contains(exNorm))
          yCenterClose && xOverlapRatio >= 0.45f && sameOrSubstring
        }
      }
      if (dupIndex == -1) {
        kept.add(candidate)
      } else {
        val existing = kept[dupIndex]
        val candScore = (candidate.confidence ?: 0.5f) + (candidate.text.length * 0.005f)
        val exScore = (existing.confidence ?: 0.5f) + (existing.text.length * 0.005f)
        if (candScore > exScore) {
          kept[dupIndex] = candidate
        }
      }
    }
    return kept.sortedWith(compareBy<OcrLine> { it.box?.top ?: Int.MAX_VALUE }.thenBy { it.box?.left ?: Int.MAX_VALUE })
  }

  private fun normalizeKey(text: String): String =
    text.lowercase(Locale.US).replace(Regex("""\s+"""), " ").trim()

  private suspend fun awaitTextTask(
    recognizer: TextRecognizer,
    inputImage: InputImage
  ): Text = suspendCancellableCoroutine { cont ->
    recognizer.process(inputImage)
      .addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
      }
      .addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWith(Result.failure(error))
      }
      .addOnCanceledListener {
        if (cont.isActive) cont.cancel()
      }
  }
}
