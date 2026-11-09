package com.baonplan.finance.domain.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class NormalizedOcrRow(
  val text: String,
  val columns: List<String>,
  val lines: List<OcrLine>,
  val top: Int,
  val bottom: Int,
  val left: Int,
  val right: Int,
  val screenshotIndex: Int
)

/**
 * Reconstructs logical horizontal rows from OCR bounding boxes so split-column layouts
 * (e.g. left label + right currency amount, or date + amount + status columns) are
 * paired accurately without relying solely on ML Kit block grouping.
 */
object OcrLayoutNormalizer {

  fun normalize(page: OcrDocumentPage): List<NormalizedOcrRow> {
    val nonBlank = page.lines
      .map { it.copy(text = sanitizeOcrGlyphs(it.text)) }
      .filter { it.text.isNotBlank() }
    if (nonBlank.isEmpty()) return emptyList()

    val boxed = nonBlank.filter { it.box != null && it.box.height > 0 && it.box.width > 0 }
    if (boxed.size < nonBlank.size / 2 || boxed.isEmpty()) {
      return nonBlank.mapIndexed { idx, line ->
        val trimmed = line.text.trim()
        NormalizedOcrRow(
          text = trimmed,
          columns = trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(trimmed) },
          lines = listOf(line),
          top = idx * 24,
          bottom = (idx + 1) * 24,
          left = 0,
          right = 1000,
          screenshotIndex = page.screenshotIndex
        )
      }
    }

    val heights = boxed.mapNotNull { it.box?.height }.sorted()
    val medianHeight = heights[heights.size / 2].coerceAtLeast(12)

    val sortedLines = nonBlank.sortedWith(
      compareBy<OcrLine> { it.box?.top ?: Int.MAX_VALUE }.thenBy { it.box?.left ?: Int.MAX_VALUE }
    )

    val clusters = mutableListOf<MutableList<OcrLine>>()
    for (line in sortedLines) {
      val box = line.box
      if (box == null) {
        clusters.add(mutableListOf(line))
        continue
      }
      var bestCluster: MutableList<OcrLine>? = null
      var bestScore = Int.MAX_VALUE
      for (cluster in clusters) {
        val sampleBoxes = cluster.mapNotNull { it.box }
        if (sampleBoxes.isEmpty()) continue
        val clusterTop = sampleBoxes.minOf { it.top }
        val clusterBottom = sampleBoxes.maxOf { it.bottom }
        val clusterCenterY = (clusterTop + clusterBottom) / 2
        val minHeight = min(box.height, (clusterBottom - clusterTop).coerceAtLeast(1)).coerceAtLeast(1)
        val overlap = max(0, min(box.bottom, clusterBottom) - max(box.top, clusterTop))
        val overlapRatio = overlap.toFloat() / minHeight.toFloat()
        val centerDelta = abs(box.centerY - clusterCenterY)

        if (overlapRatio >= 0.45f || centerDelta <= (medianHeight * 0.55f)) {
          if (centerDelta < bestScore) {
            bestScore = centerDelta
            bestCluster = cluster
          }
        }
      }
      if (bestCluster != null) {
        bestCluster.add(line)
      } else {
        clusters.add(mutableListOf(line))
      }
    }

    return clusters.map { cluster ->
      val ordered = cluster.sortedBy { it.box?.left ?: 0 }
      val columns = mutableListOf<String>()
      val currentCol = StringBuilder()
      var prevRight: Int? = null
      for (item in ordered) {
        val t = item.text.trim()
        if (t.isEmpty()) continue
        val box = item.box
        val gap = if (prevRight != null && box != null) box.left - prevRight else 0
        if (currentCol.isEmpty()) {
          currentCol.append(t)
        } else if (gap > medianHeight * 0.9f) {
          columns.add(currentCol.toString().trim())
          currentCol.clear()
          currentCol.append(t)
        } else {
          currentCol.append(' ').append(t)
        }
        prevRight = box?.right ?: prevRight
      }
      if (currentCol.isNotEmpty()) {
        columns.add(currentCol.toString().trim())
      }
      val rowText = columns.joinToString(" | ")
      val boxes = ordered.mapNotNull { it.box }
      NormalizedOcrRow(
        text = rowText,
        columns = columns.ifEmpty { listOf(rowText) },
        lines = ordered,
        top = boxes.minOfOrNull { it.top } ?: 0,
        bottom = boxes.maxOfOrNull { it.bottom } ?: 0,
        left = boxes.minOfOrNull { it.left } ?: 0,
        right = boxes.maxOfOrNull { it.right } ?: 0,
        screenshotIndex = page.screenshotIndex
      )
    }.sortedWith(compareBy<NormalizedOcrRow> { it.top }.thenBy { it.left })
  }

  private fun sanitizeOcrGlyphs(raw: String): String =
    raw
      .replace('\u00A0', ' ')
      .replace('\u2007', ' ')
      .replace('\u2009', ' ')
      .replace('\u202F', ' ')
      .replace('\u0131', 'i')
      .replace('\u0130', 'I')
}
