package com.baonplan.finance.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Handles temporary scan image storage, EXIF correction, safe downsampling, and
 * privacy-safe cache cleanup under cacheDir/ocr_scan_tmp/.
 * Never writes image metadata or OCR contents to logs.
 */
object ImageScanPreprocessor {

  private const val TEMP_DIR_NAME = "ocr_scan_tmp"
  private const val OCR_MAX_LONG_EDGE_PX = 2200
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
   * EXIF-corrected private JPEG inside cacheDir/ocr_scan_tmp/ without persisting original EXIF metadata.
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
    val bitmap = decodeScaledRotatedBitmap(sourceFile, OCR_MAX_LONG_EDGE_PX, extraRotationDegrees = 0)
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
    val bitmap = decodeScaledRotatedBitmap(
      file = sourceFile,
      maxLongEdgePx = OCR_MAX_LONG_EDGE_PX,
      extraRotationDegrees = extraRotationDegrees
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
      decodeScaledRotatedBitmap(file, OCR_MAX_LONG_EDGE_PX, rotationDegrees)
        ?: error("Unable to decode screenshot for text recognition.")
    }
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
      decodeScaledRotatedBitmap(file, maxLongEdgePx = 1600, extraRotationDegrees = 0)
    }.getOrNull()
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
