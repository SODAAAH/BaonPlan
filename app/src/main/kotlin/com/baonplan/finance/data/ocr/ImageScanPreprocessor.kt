package com.baonplan.finance.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class OcrBitmapStrip(
  val bitmap: Bitmap,
  val yOffsetPx: Int,
  val fullWidthPx: Int,
  val fullHeightPx: Int
)

/**
 * Handles temporary scan image storage, EXIF correction, tall-screenshot strip slicing,
 * low-res small-font upscaling, optional contrast enhancement, and privacy-safe cache
 * cleanup under cacheDir/ocr_scan_tmp/.
 * Never writes image metadata or OCR contents to logs.
 */
object ImageScanPreprocessor {

  private const val TEMP_DIR_NAME = "ocr_scan_tmp"
  private const val OCR_MAX_LONG_EDGE_PX = 2800
  private const val OCR_TALL_MAX_SHORT_EDGE_PX = 1440
  private const val OCR_TALL_MAX_TOTAL_HEIGHT_PX = 16000
  private const val OCR_STRIP_MAX_HEIGHT_PX = 2200
  private const val OCR_STRIP_OVERLAP_PX = 240
  private const val TALL_SCROLL_ASPECT_RATIO = 2.65f
  private const val OCR_UPSCALE_MIN_SHORT_EDGE_PX = 720
  private const val OCR_UPSCALE_TARGET_SHORT_EDGE_PX = 1080
  private const val OCR_MAX_PIXELS = 18_000_000L
  private const val THUMB_MAX_EDGE_PX = 420
  private const val CAMERA_JPEG_QUALITY = 95
  private const val ATTACHMENT_JPEG_QUALITY = 92

  fun tempDir(context: Context): File =
    File(context.cacheDir, TEMP_DIR_NAME).apply {
      if (!exists()) mkdirs()
    }

  fun sweepStaleTempFiles(context: Context, maxAgeMillis: Long = 3_600_000L) {
    val dir = File(context.cacheDir, TEMP_DIR_NAME)
    if (!dir.exists() || !dir.isDirectory) return
    val cutoff = System.currentTimeMillis() - maxAgeMillis
    dir.listFiles()?.forEach { file ->
      if (file.isFile && file.lastModified() < cutoff) {
        runCatching { file.delete() }
      }
    }
  }

  fun clearAllTempFiles(context: Context) {
    val dir = File(context.cacheDir, TEMP_DIR_NAME)
    if (!dir.exists() || !dir.isDirectory) return
    dir.listFiles()?.forEach { file ->
      runCatching { if (file.isFile) file.delete() }
    }
  }

  fun deleteTempFile(file: File?) {
    if (file == null) return
    runCatching {
      if (file.exists() && file.isFile) file.delete()
    }
  }

  fun createCameraTempFileAndUri(context: Context): Pair<File, Uri> {
    val dir = tempDir(context)
    val file = File(dir, "cam_raw_${System.currentTimeMillis()}_${System.nanoTime()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return file to uri
  }

  /**
   * Copies and normalizes a Photo Picker URI (or camera output file) into a compact,
   * EXIF-corrected private PNG/JPEG inside cacheDir/ocr_scan_tmp/ without persisting EXIF metadata.
   * Preserves crisp horizontal resolution even on tall scrolling screenshots.
   */
  suspend fun importUriToPrivateTempFile(context: Context, uri: Uri): Result<File> =
    withContext(Dispatchers.IO) {
      runCatching {
        val rawTemp = File(tempDir(context), "pick_raw_${System.nanoTime()}.tmp")
        try {
          context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(rawTemp).use { output ->
              input.copyTo(output)
            }
          } ?: error("Unable to open selected image.")

          if (!rawTemp.exists() || rawTemp.length() <= 0L) {
            error("Selected image file is empty or unreadable.")
          }

          normalizeImageFileToPrivateTemp(context, rawTemp, preferLosslessPng = true).getOrThrow()
        } finally {
          deleteTempFile(rawTemp)
        }
      }
    }

  suspend fun normalizeCapturedFile(context: Context, rawCapturedFile: File): Result<File> =
    withContext(Dispatchers.IO) {
      try {
        if (!rawCapturedFile.exists() || rawCapturedFile.length() <= 0L) {
          Result.failure(IllegalStateException("Camera did not save a readable photo."))
        } else {
          normalizeImageFileToPrivateTemp(context, rawCapturedFile, preferLosslessPng = false)
        }
      } finally {
        deleteTempFile(rawCapturedFile)
      }
    }

  private fun normalizeImageFileToPrivateTemp(
    context: Context,
    sourceFile: File,
    preferLosslessPng: Boolean
  ): Result<File> = runCatching {
    val bitmap = decodeForOcrStaging(sourceFile, extraRotationDegrees = 0, upscaleSmallFonts = false)
      ?: error("Unsupported or corrupted image format.")
    try {
      val ext = if (preferLosslessPng) "png" else "jpg"
      val destFile = File(tempDir(context), "staged_${System.currentTimeMillis()}_${System.nanoTime()}.$ext")
      FileOutputStream(destFile).use { out ->
        val ok = if (preferLosslessPng) {
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
          bitmap.compress(Bitmap.CompressFormat.JPEG, CAMERA_JPEG_QUALITY, out)
        }
        if (!ok) {
          error("Failed to prepare image for scanning.")
        }
      }
      destFile
    } finally {
      bitmap.recycle()
    }
  }

  /**
   * Saves a private copy of a staged screenshot to app-internal storage with any user
   * manual rotation baked into pixel orientation (and EXIF stripped).
   */
  fun writeRotatedAttachmentJpeg(
    sourceFile: File,
    destFile: File,
    extraRotationDegrees: Int
  ): Boolean {
    val bitmap = decodeForOcrStaging(
      file = sourceFile,
      extraRotationDegrees = extraRotationDegrees,
      upscaleSmallFonts = false
    ) ?: return false
    return try {
      FileOutputStream(destFile).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, ATTACHMENT_JPEG_QUALITY, out)
      }
    } finally {
      bitmap.recycle()
    }
  }

  suspend fun decodeBitmapForOcr(
    file: File,
    rotationDegrees: Int = 0
  ): Result<Bitmap> = withContext(Dispatchers.Default) {
    runCatching {
      decodeForOcrStaging(file, rotationDegrees, upscaleSmallFonts = true)
        ?: error("Unable to decode screenshot for text recognition.")
    }
  }

  /**
   * Decodes one full upright image, upscaling low-res crops when helpful and slicing tall
   * scrolling screenshots into overlapping vertical strips so horizontal text sharpness stays high.
   */
  suspend fun decodeBitmapStripsForOcr(
    file: File,
    rotationDegrees: Int = 0
  ): Result<List<OcrBitmapStrip>> = withContext(Dispatchers.Default) {
    runCatching {
      val full = decodeForOcrStaging(file, rotationDegrees, upscaleSmallFonts = true)
        ?: error("Unable to decode screenshot for text recognition.")
      val fullW = full.width
      val fullH = full.height

      if (fullH <= 3100 || fullH <= (fullW * TALL_SCROLL_ASPECT_RATIO).toInt()) {
        return@runCatching listOf(
          OcrBitmapStrip(
            bitmap = full,
            yOffsetPx = 0,
            fullWidthPx = fullW,
            fullHeightPx = fullH
          )
        )
      }

      val strips = mutableListOf<OcrBitmapStrip>()
      try {
        var y = 0
        while (y < fullH) {
          val h = minOf(OCR_STRIP_MAX_HEIGHT_PX, fullH - y)
          val slice = Bitmap.createBitmap(full, 0, y, fullW, h)
          strips.add(
            OcrBitmapStrip(
              bitmap = slice,
              yOffsetPx = y,
              fullWidthPx = fullW,
              fullHeightPx = fullH
            )
          )
          if (y + h >= fullH) break
          y += (OCR_STRIP_MAX_HEIGHT_PX - OCR_STRIP_OVERLAP_PX).coerceAtLeast(1)
        }
        strips
      } catch (t: Throwable) {
        strips.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        throw t
      } finally {
        if (!full.isRecycled) full.recycle()
      }
    }
  }

  /**
   * Applies adaptive contrast/brightness stretching + monochrome normalization to improve OCR
   * on faded gray labels (e.g. "Remaining", "Installment", "Paid: 3/24") as well as dark-mode
   * or low-contrast screenshots. Caller must recycle returned Bitmap.
   */
  fun createContrastEnhancedCopy(source: Bitmap): Bitmap {
    val dest = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(dest)

    val lightBackground = isLikelyLightBackground(source)
    val contrast: Float
    val translate: Float
    if (lightBackground) {
      // Darken medium/faded grays (#7A8299) toward charcoal while clipping light-gray UI chrome to white
      contrast = 1.9f
      translate = -165f
    } else {
      contrast = 1.55f
      translate = (-0.42f * contrast + 0.5f) * 255f
    }

    val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
    val contrastMatrix = ColorMatrix(
      floatArrayOf(
        contrast, 0f, 0f, 0f, translate,
        0f, contrast, 0f, 0f, translate,
        0f, 0f, contrast, 0f, translate,
        0f, 0f, 0f, 1f, 0f
      )
    )
    grayMatrix.postConcat(contrastMatrix)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(grayMatrix)
    }
    canvas.drawBitmap(source, 0f, 0f, paint)
    return dest
  }

  private fun isLikelyLightBackground(bitmap: Bitmap): Boolean {
    val cols = 6
    val rows = 8
    var lumaSum = 0L
    var count = 0
    val stepX = (bitmap.width / (cols + 1)).coerceAtLeast(1)
    val stepY = (bitmap.height / (rows + 1)).coerceAtLeast(1)
    for (r in 1..rows) {
      val y = (r * stepY).coerceIn(0, bitmap.height - 1)
      for (c in 1..cols) {
        val x = (c * stepX).coerceIn(0, bitmap.width - 1)
        val px = bitmap.getPixel(x, y)
        val luma = ((px shr 16 and 0xff) * 299 + (px shr 8 and 0xff) * 587 + (px and 0xff) * 114) / 1000
        lumaSum += luma
        count++
      }
    }
    return count == 0 || (lumaSum / count) >= 128
  }

  suspend fun decodeThumbnail(
    file: File,
    rotationDegrees: Int = 0
  ): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
      decodeScaledRotatedBitmap(file, THUMB_MAX_EDGE_PX, rotationDegrees)
    }.getOrNull()
  }

  suspend fun decodeAttachmentPreview(
    file: File
  ): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
      decodeForOcrStaging(file, extraRotationDegrees = 0, upscaleSmallFonts = false)
    }.getOrNull()
  }

  private fun decodeForOcrStaging(
    file: File,
    extraRotationDegrees: Int,
    upscaleSmallFonts: Boolean
  ): Bitmap? {
    if (!file.exists() || !file.isFile || file.length() <= 0L) return null
    val exifTransform = readExifTransform(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val normalizedExtra = ((extraRotationDegrees % 360) + 360) % 360
    val totalRotation = (exifTransform.degrees + normalizedExtra) % 360
    val swapDims = totalRotation == 90 || totalRotation == 270
    val logicalW = if (swapDims) bounds.outHeight else bounds.outWidth
    val logicalH = if (swapDims) bounds.outWidth else bounds.outHeight

    val shortEdge = minOf(logicalW, logicalH)
    val longEdge = maxOf(logicalW, logicalH)
    val isTallScroll = longEdge > shortEdge * TALL_SCROLL_ASPECT_RATIO

    // Determine safe sample size preserving horizontal readability on tall scrolling screenshots
    val targetSampleLongEdge = if (isTallScroll) {
      minOf(longEdge, OCR_TALL_MAX_TOTAL_HEIGHT_PX)
    } else {
      OCR_MAX_LONG_EDGE_PX
    }
    var inSampleSize = computeInSampleSizeKeepingMinWidth(
      rawW = bounds.outWidth,
      rawH = bounds.outHeight,
      isTallScroll = isTallScroll,
      targetMaxLongEdge = targetSampleLongEdge
    )

    // Guard total pixels against OOM
    while ((bounds.outWidth.toLong() / inSampleSize) * (bounds.outHeight.toLong() / inSampleSize) > OCR_MAX_PIXELS) {
      inSampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = inSampleSize.coerceAtLeast(1)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

    // Apply orientation first so width/height constraints operate in final logical space
    if (exifTransform.flipHorizontal || totalRotation != 0) {
      val matrix = Matrix()
      if (exifTransform.flipHorizontal) {
        matrix.postScale(-1f, 1f)
      }
      if (totalRotation != 0) {
        matrix.postRotate(totalRotation.toFloat())
      }
      val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (rotated !== bitmap) {
        bitmap.recycle()
        bitmap = rotated
      }
    }

    val curW = bitmap.width
    val curH = bitmap.height
    val curShort = minOf(curW, curH)
    val curLong = maxOf(curW, curH)
    val isNowTall = curLong > curShort * TALL_SCROLL_ASPECT_RATIO

    val scaleFactor: Float? = when {
      isNowTall && curShort > OCR_TALL_MAX_SHORT_EDGE_PX ->
        OCR_TALL_MAX_SHORT_EDGE_PX.toFloat() / curShort.toFloat()
      isNowTall && curLong > OCR_TALL_MAX_TOTAL_HEIGHT_PX ->
        OCR_TALL_MAX_TOTAL_HEIGHT_PX.toFloat() / curLong.toFloat()
      !isNowTall && curLong > OCR_MAX_LONG_EDGE_PX ->
        OCR_MAX_LONG_EDGE_PX.toFloat() / curLong.toFloat()
      upscaleSmallFonts && curShort in 120 until OCR_UPSCALE_MIN_SHORT_EDGE_PX && !isNowTall -> {
        val factor = OCR_UPSCALE_TARGET_SHORT_EDGE_PX.toFloat() / curShort.toFloat()
        factor.coerceAtMost(2.5f)
      }
      else -> null
    }

    if (scaleFactor != null && scaleFactor != 1f) {
      val targetW = (curW * scaleFactor).toInt().coerceAtLeast(1)
      val targetH = (curH * scaleFactor).toInt().coerceAtLeast(1)
      if (targetW.toLong() * targetH.toLong() <= OCR_MAX_PIXELS) {
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled !== bitmap) {
          bitmap.recycle()
          bitmap = scaled
        }
      }
    }

    return bitmap
  }

  private fun computeInSampleSizeKeepingMinWidth(
    rawW: Int,
    rawH: Int,
    isTallScroll: Boolean,
    targetMaxLongEdge: Int
  ): Int {
    val longEdge = maxOf(rawW, rawH)
    val shortEdge = minOf(rawW, rawH)
    var inSampleSize = 1
    if (isTallScroll) {
      while (shortEdge / (inSampleSize * 2) >= OCR_TALL_MAX_SHORT_EDGE_PX &&
        longEdge / (inSampleSize * 2) >= 1200
      ) {
        inSampleSize *= 2
      }
    } else {
      while (longEdge / (inSampleSize * 2) >= targetMaxLongEdge) {
        inSampleSize *= 2
      }
    }
    return inSampleSize.coerceAtLeast(1)
  }

  private fun decodeScaledRotatedBitmap(
    file: File,
    maxLongEdgePx: Int,
    extraRotationDegrees: Int
  ): Bitmap? {
    if (!file.exists() || !file.isFile || file.length() <= 0L) return null

    val exifTransform = readExifTransform(file)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdgePx)
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

    val currentLongEdge = maxOf(bitmap.width, bitmap.height)
    if (currentLongEdge > maxLongEdgePx) {
      val scale = maxLongEdgePx.toFloat() / currentLongEdge.toFloat()
      val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
      val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
      val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
      if (scaled !== bitmap) {
        bitmap.recycle()
        bitmap = scaled
      }
    }

    val normalizedExtra = ((extraRotationDegrees % 360) + 360) % 360
    val needsMatrix = exifTransform.degrees != 0 || exifTransform.flipHorizontal || normalizedExtra != 0
    if (needsMatrix) {
      val matrix = Matrix()
      if (exifTransform.flipHorizontal) {
        matrix.postScale(-1f, 1f)
      }
      val totalRotation = (exifTransform.degrees + normalizedExtra) % 360
      if (totalRotation != 0) {
        matrix.postRotate(totalRotation.toFloat())
      }
      val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (rotated !== bitmap) {
        bitmap.recycle()
        bitmap = rotated
      }
    }

    return bitmap
  }

  private fun computeInSampleSize(width: Int, height: Int, maxLongEdgePx: Int): Int {
    val longEdge = maxOf(width, height)
    var inSampleSize = 1
    while (longEdge / (inSampleSize * 2) >= maxLongEdgePx) {
      inSampleSize *= 2
    }
    return inSampleSize.coerceAtLeast(1)
  }

  private data class ExifTransform(val degrees: Int, val flipHorizontal: Boolean)

  private fun readExifTransform(file: File): ExifTransform {
    val orientation = runCatching {
      ExifInterface(file.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
      )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    return when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
      ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
      ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
      ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
      ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
      else -> ExifTransform(0, false)
    }
  }
}
