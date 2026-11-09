package com.baonplan.finance.data.ocr

import android.graphics.Bitmap
import com.baonplan.finance.domain.ocr.OcrBoundingBox
import com.baonplan.finance.domain.ocr.OcrDocumentPage
import com.baonplan.finance.domain.ocr.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 100% on-device offline ML Kit Latin Text Recognition wrapper.
 * Takes upright preprocessed Bitmaps so ML Kit line bounding boxes map directly
 * to screen rows. Recycles input bitmaps immediately and logs zero OCR content.
 */
object MlKitDebtOcrDataSource {

  private const val MIN_READABLE_ALPHANUMERIC_CHARS = 5

  suspend fun recognizeBitmap(
    screenshotIndex: Int,
    bitmap: Bitmap
  ): Result<OcrDocumentPage> = withContext(Dispatchers.Default) {
    val width = bitmap.width
    val height = bitmap.height
    var recognizer: TextRecognizer? = null
    try {
      recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
      val inputImage = InputImage.fromBitmap(bitmap, 0)
      val visionText = awaitTextTask(recognizer, inputImage)
      val lines = visionText.textBlocks.flatMap { block ->
        block.lines.map { line ->
          val rect = line.boundingBox
          OcrLine(
            text = line.text,
            box = rect?.let { OcrBoundingBox(it.left, it.top, it.right, it.bottom) }
          )
        }
      }
      val rawText = visionText.text
      val alnumCount = rawText.count { it.isLetterOrDigit() }
      if (lines.isEmpty() || alnumCount < MIN_READABLE_ALPHANUMERIC_CHARS) {
        Result.failure(
          IllegalStateException(
            "Couldn't read text from screenshot #${screenshotIndex + 1}. Try rotating it, choosing a clearer image, or entering details manually."
          )
        )
      } else {
        Result.success(
          OcrDocumentPage(
            screenshotIndex = screenshotIndex,
            width = width,
            height = height,
            lines = lines,
            rawFullText = rawText
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
      if (!bitmap.isRecycled) {
        bitmap.recycle()
      }
    }
  }

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
