package com.baonplan.finance.domain.ocr

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrCell(
  val text: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val confidence: Float? = null,
  val blockId: Int? = null
) {
  val width: Int get() = (right - left).coerceAtLeast(0)
  val height: Int get() = (bottom - top).coerceAtLeast(0)
  val centerX: Int get() = left + width / 2
  val centerY: Int get() = top + height / 2
}

data class NormalizedOcrRow(
  val text: String,
  val columns: List<String>,
  val lines: List<OcrLine>,
  val top: Int,
  val bottom: Int,
  val left: Int,
  val right: Int,
  val screenshotIndex: Int,
  val cells: List<OcrCell> = emptyList(),
  val averageConfidence: Float? = null
)

data class NormalizedPageResult(
  val rows: List<NormalizedOcrRow>,
  val audits: List<TextCorrectionAudit>
)

/**
 * Reconstructs logical horizontal rows and 2D cells from OCR bounding boxes so
 * split-column, metric-grid, hero-above-label, right-aligned status pills, and
 * separated currency tokens (e.g. "₱ | 1,087.38") are paired accurately while
 * recording every glyph/emblem repair in [TextCorrectionAudit].
 */
object OcrLayoutNormalizer {

  private val standaloneCurrencyPrefixRegex = Regex(
    """^(?:₱|PHP|Php|PhP|P\.?|Ph\.?|F|£)$"""
  )

  private val startsWithMoneyDigitsRegex = Regex(
    """^\d{1,3}(?:[,\s]\d{3})*(?:[.,]\d{1,2})?(?:\b|$)"""
  )

  private val systemChromeClockOrSignalRegex = Regex(
    """^(?:\d{1,2}:\d{2}(?:\s*[ap]m)?|5g|4g|lte|volte|vo\s*wifi|wifi|\d{1,3}%|[<>\u25A0\u25CF\u25B2\|/\\]{1,3}|kb/s|mb/s)$""",
    RegexOption.IGNORE_CASE
  )

  private val financialGuardRegex = Regex(
    """\b(?:loan|balance|due|installment|repayment|principal|amount|paid|interest|fee|spay|sloan|gloan|ggives|billease|maya|tala|atome|juanhand|skyro|cimb|home\s*credit|peso|php)\b|₱""",
    RegexOption.IGNORE_CASE
  )

  // Leading circular logo / icon OCR noise such as "G))", "(G)", "[G]", "6))", "M))", "©", "®", "◉", "•"
  private val leadingEmblemNoiseRegex = Regex(
    """^(?:[GM6C]\s*[)\]}»›>]{1,4}|[({\[]\s*[A-Za-z0-9•●◉©®]{1,2}\s*[)}\]]|[©®•●◉▪▫◆◇▶►]+)\s*(?=(?:₱|PHP|Php|PhP|Ph?\.?\d|[A-Z]))"""
  )

  private val standaloneEmblemTokenRegex = Regex(
    """^(?:[GM6C]\s*[)\]}»›>]{1,4}|[({\[]\s*[A-Za-z0-9•●◉©®]{1,2}\s*[)}\]]?|[©®•●◉▪▫◆◇▶►]+|[G6])$"""
  )

  // Trailing UI copy/chevron glyph noise after reference codes or rows
  private val trailingUiGlyphRegex = Regex(
    """\s+(?:\[\]|❐|⧉|📋|›|»|>)$"""
  )

  // Context-guarded peso prefix + numeric body (e.g. "P1,000.00", "F1,087.38", "£51.78", "₱1 087.38", "₱51,78")
  private val currencyTokenRepairRegex = Regex(
    """(?<![A-Za-z0-9])(₱|PHP|Php|PhP|Ph\.?|P\.?|F|£)\s*([0-9OolI]{1,3}(?:[,\s][0-9OolI]{3})+(?:[.,]\s?[0-9OolI]{1,2})?|[0-9OolI]{1,3}(?:\.[0-9OolI]{3})+(?:\.[0-9OolI]{2})|[0-9OolI]+(?:[.,]\s?[0-9OolI]{1,2})?)(?![\d%]|\.\d|,\d)"""
  )

  // Standalone numeric money/count tokens with O/I/l slips in numeric positions (e.g. "1,O87.38" or "2l")
  private val standaloneNumericSlipRegex = Regex(
    """(?<![A-Za-z0-9./-])([0-9]{1,3}(?:,[0-9OolI]{3})+(?:\.[0-9OolI]{2})?|[0-9]+[.,][0-9OolI]{2})(?![A-Za-z0-9%./-])"""
  )

  fun normalize(page: OcrDocumentPage): List<NormalizedOcrRow> =
    normalizeWithAudits(page).rows

  fun normalizeWithAudits(page: OcrDocumentPage): NormalizedPageResult {
    val audits = mutableListOf<TextCorrectionAudit>()
    val deduplicatedLines = deduplicateOverlappingLines(page.lines)

    val sanitized = deduplicatedLines
      .map { line ->
        val cleanedLine = cleanAndAuditText(line.text, page.screenshotIndex, audits)
        val cleanedElements = line.elements.map { el ->
          el.copy(text = cleanAndAuditText(el.text, page.screenshotIndex, null))
        }
        line.copy(text = cleanedLine, elements = cleanedElements)
      }
      .filter { it.text.isNotBlank() }

    if (sanitized.isEmpty()) {
      return NormalizedPageResult(emptyList(), audits.distinct())
    }

    val boxedCount = sanitized.count { it.box != null && it.box.height > 0 && it.box.width > 0 }
    if (boxedCount < sanitized.size / 2 || boxedCount == 0) {
      val rows = sanitized.mapIndexed { idx, line ->
        val trimmed = line.text.trim()
        val cols = mergeStandaloneCurrencyColumns(
          trimmed.split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(trimmed) }
        )
        val joined = cols.joinToString(" | ")
        val cells = cols.mapIndexed { colIdx, col ->
          OcrCell(
            text = col,
            left = colIdx * 300,
            top = idx * 24,
            right = (colIdx + 1) * 300 - 20,
            bottom = (idx + 1) * 24,
            confidence = line.confidence,
            blockId = line.blockId
          )
        }
        NormalizedOcrRow(
          text = joined,
          columns = cols,
          lines = listOf(line),
          top = idx * 24,
          bottom = (idx + 1) * 24,
          left = 0,
          right = 1000,
          screenshotIndex = page.screenshotIndex,
          cells = cells,
          averageConfidence = line.confidence
        )
      }
      return NormalizedPageResult(rows, audits.distinct())
    }

    val filtered = filterSystemChromeLines(sanitized, page.height)
    val working = filtered.ifEmpty { sanitized }

    val heights = working.mapNotNull { it.box?.height }.filter { it > 0 }.sorted()
    val medianHeight = if (heights.isNotEmpty()) heights[heights.size / 2].coerceAtLeast(12) else 24

    // Split wide lines that have large internal element horizontal gaps (e.g. merged 2-column row)
    val splitPieces = splitWideMultiColumnLines(working, medianHeight)

    val sortedPieces = splitPieces.sortedWith(
      compareBy<LinePiece> { it.box?.top ?: Int.MAX_VALUE }.thenBy { it.box?.left ?: Int.MAX_VALUE }
    )

    val clusters = mutableListOf<MutableList<LinePiece>>()
    for (piece in sortedPieces) {
      val box = piece.box
      if (box == null) {
        clusters.add(mutableListOf(piece))
        continue
      }
      var bestCluster: MutableList<LinePiece>? = null
      var bestScore = Int.MAX_VALUE

      for (cluster in clusters) {
        val sampleBoxes = cluster.mapNotNull { it.box }
        if (sampleBoxes.isEmpty()) continue
        val clusterTop = sampleBoxes.minOf { it.top }
        val clusterBottom = sampleBoxes.maxOf { it.bottom }
        val clusterCenterY = (clusterTop + clusterBottom) / 2
        val clusterHeight = (clusterBottom - clusterTop).coerceAtLeast(1)
        val minHeight = min(box.height, clusterHeight).coerceAtLeast(1)
        val overlap = max(0, min(box.bottom, clusterBottom) - max(box.top, clusterTop))
        val overlapRatio = overlap.toFloat() / minHeight.toFloat()
        val centerDelta = abs(box.centerY - clusterCenterY)

        // Prevent vertically stacked lines (sharing significant horizontal span without vertical overlap)
        // from being collapsed into a single horizontal row.
        val horizontallyStackedBelow = sampleBoxes.any { existing ->
          val xOverlap = max(0, min(box.right, existing.right) - max(box.left, existing.left))
          val narrowerW = min(box.width, existing.width).coerceAtLeast(1)
          xOverlap.toFloat() / narrowerW.toFloat() > 0.35f && overlapRatio < 0.25f
        }
        if (horizontallyStackedBelow) continue

        // Support tall hero currency values AND slightly offset right-aligned status pills ("paid", "overdue")
        val isRightAlignedPill = isStatusPillToken(piece.text) &&
          sampleBoxes.none { existing ->
            val xOverlap = max(0, min(box.right, existing.right) - max(box.left, existing.left))
            xOverlap > 0
          }
        val isCompatibleRowHeight = overlapRatio >= 0.35f ||
          (centerDelta <= (medianHeight * 0.58f) && max(box.height, clusterHeight) <= medianHeight * 2.8f) ||
          (isRightAlignedPill && centerDelta <= (medianHeight * 0.85f))

        if (isCompatibleRowHeight && centerDelta < bestScore) {
          bestScore = centerDelta
          bestCluster = cluster
        }
      }

      if (bestCluster != null) {
        bestCluster.add(piece)
      } else {
        clusters.add(mutableListOf(piece))
      }
    }

    val normalizedRows = clusters.map { cluster ->
      val ordered = cluster.sortedBy { it.box?.left ?: 0 }
      val rawCells = ordered.mapNotNull { piece ->
        val t = piece.text.trim()
        if (t.isEmpty()) null else OcrCell(
          text = t,
          left = piece.box?.left ?: 0,
          top = piece.box?.top ?: 0,
          right = piece.box?.right ?: 1000,
          bottom = piece.box?.bottom ?: 0,
          confidence = piece.sourceLine.confidence,
          blockId = piece.sourceLine.blockId
        )
      }

      // First drop leading standalone circular emblem cells ("G))", "(G)") when real text follows on the row,
      // then merge standalone currency tokens like ["₱", "1,087.38"] on the same visual band
      val emblemStrippedCells = stripLeadingStandaloneEmblemCells(
        cells = rawCells,
        screenshotIndex = page.screenshotIndex,
        audits = audits
      )
      val currencyMergedCells = mergeAdjacentCurrencyCells(emblemStrippedCells, medianHeight)

      // Then build logical columns split at wider horizontal gaps
      val columns = mutableListOf<String>()
      val finalCells = mutableListOf<OcrCell>()
      var currentBuilder: OcrCell? = null

      for (cell in currencyMergedCells) {
        val prev = currentBuilder
        if (prev == null) {
          currentBuilder = cell
        } else {
          val gap = cell.left - prev.right
          if (gap > medianHeight * 0.9f) {
            val cleanedPrev = cleanAndAuditText(prev.text, page.screenshotIndex, audits)
            columns.add(cleanedPrev)
            finalCells.add(prev.copy(text = cleanedPrev))
            currentBuilder = cell
          } else {
            val combinedConf = averageConfidences(listOfNotNull(prev.confidence, cell.confidence))
            val combinedRaw = "${prev.text} ${cell.text}".trim()
            currentBuilder = OcrCell(
              text = combinedRaw,
              left = min(prev.left, cell.left),
              top = min(prev.top, cell.top),
              right = max(prev.right, cell.right),
              bottom = max(prev.bottom, cell.bottom),
              confidence = combinedConf,
              blockId = prev.blockId ?: cell.blockId
            )
          }
        }
      }
      if (currentBuilder != null) {
        val cleanedLast = cleanAndAuditText(currentBuilder.text, page.screenshotIndex, audits)
        columns.add(cleanedLast)
        finalCells.add(currentBuilder.copy(text = cleanedLast))
      }

      val rowText = columns.joinToString(" | ")
      val sourceLines = ordered.map { it.sourceLine }.distinct()
      val boxes = finalCells
      val avgConf = averageConfidences(sourceLines.mapNotNull { it.confidence })
      NormalizedOcrRow(
        text = rowText,
        columns = columns.ifEmpty { listOf(rowText) },
        lines = sourceLines,
        top = boxes.minOfOrNull { it.top } ?: 0,
        bottom = boxes.maxOfOrNull { it.bottom } ?: 0,
        left = boxes.minOfOrNull { it.left } ?: 0,
        right = boxes.maxOfOrNull { it.right } ?: 0,
        screenshotIndex = page.screenshotIndex,
        cells = finalCells,
        averageConfidence = avgConf
      )
    }.sortedWith(compareBy<NormalizedOcrRow> { it.top }.thenBy { it.left })

    return NormalizedPageResult(normalizedRows, audits.distinct())
  }

  private fun deduplicateOverlappingLines(lines: List<OcrLine>): List<OcrLine> {
    if (lines.size < 2) return lines
    val kept = mutableListOf<OcrLine>()
    for (candidate in lines) {
      val cBox = candidate.box
      if (cBox == null) {
        kept.add(candidate)
        continue
      }
      val duplicateIdx = kept.indexOfFirst { existing ->
        val eBox = existing.box ?: return@indexOfFirst false
        val interW = max(0, min(cBox.right, eBox.right) - max(cBox.left, eBox.left))
        val interH = max(0, min(cBox.bottom, eBox.bottom) - max(cBox.top, eBox.top))
        val interArea = interW.toLong() * interH.toLong()
        if (interArea <= 0L) return@indexOfFirst false
        val cArea = (cBox.width.toLong() * cBox.height.toLong()).coerceAtLeast(1L)
        val eArea = (eBox.width.toLong() * eBox.height.toLong()).coerceAtLeast(1L)
        val minArea = min(cArea, eArea)
        val overlapFraction = interArea.toFloat() / minArea.toFloat()
        overlapFraction > 0.72f &&
          (existing.text.trim().equals(candidate.text.trim(), ignoreCase = true) ||
            existing.text.contains(candidate.text.trim(), ignoreCase = true) ||
            candidate.text.contains(existing.text.trim(), ignoreCase = true))
      }
      if (duplicateIdx == -1) {
        kept.add(candidate)
      } else {
        val existing = kept[duplicateIdx]
        if (candidate.text.trim().length > existing.text.trim().length) {
          kept[duplicateIdx] = candidate
        }
      }
    }
    return kept
  }

  private fun cleanAndAuditText(
    raw: String,
    screenshotIndex: Int,
    audits: MutableList<TextCorrectionAudit>?
  ): String {
    var current = sanitizeOcrGlyphs(raw).trim()
    if (current.isEmpty()) return current
    val originalSnapshot = current

    // 1. Strip circular app-emblem noise ("G)) ₱1,000.00 GCash Cash Loan" -> "₱1,000.00 GCash Cash Loan")
    val withoutEmblem = leadingEmblemNoiseRegex.replace(current, "")
    if (withoutEmblem != current && withoutEmblem.isNotBlank()) {
      audits?.add(
        TextCorrectionAudit(
          rawText = originalSnapshot,
          normalizedText = withoutEmblem,
          reason = "Removed circular app-icon OCR noise",
          screenshotIndex = screenshotIndex
        )
      )
      current = withoutEmblem
    }

    // 2. Strip trailing copy/chevron glyphs
    val withoutTrailingGlyph = trailingUiGlyphRegex.replace(current, "")
    if (withoutTrailingGlyph != current && withoutTrailingGlyph.isNotBlank()) {
      current = withoutTrailingGlyph
    }

    // 3. Context-guarded peso prefix + numeric body normalization
    val currencyRepaired = currencyTokenRepairRegex.replace(current) { match ->
      val rawPrefix = match.groupValues[1]
      val rawNum = match.groupValues[2]
      val isAmbiguousSingleP = rawPrefix == "P" && rawNum.none { it == '.' || it == ',' } && rawNum.length <= 2
      if (isAmbiguousSingleP) {
        match.value
      } else {
        val normalizedPrefix = when {
          rawPrefix == "₱" -> "₱"
          rawPrefix.equals("PHP", ignoreCase = true) -> "PHP "
          else -> "₱"
        }
        val normalizedNum = repairNumericAmountToken(rawNum)
        "$normalizedPrefix$normalizedNum"
      }
    }
    if (currencyRepaired != current) {
      audits?.add(
        TextCorrectionAudit(
          rawText = current,
          normalizedText = currencyRepaired,
          reason = "Normalized peso currency/digit OCR glyphs",
          screenshotIndex = screenshotIndex
        )
      )
      current = currencyRepaired
    }

    // 4. Context-guarded standalone numeric token O/I/l repair (only when surrounded by digits/separators)
    val numericRepaired = standaloneNumericSlipRegex.replace(current) { match ->
      repairNumericAmountToken(match.groupValues[1])
    }
    if (numericRepaired != current) {
      audits?.add(
        TextCorrectionAudit(
          rawText = current,
          normalizedText = numericRepaired,
          reason = "Repaired letter-for-digit OCR slip in numeric amount",
          screenshotIndex = screenshotIndex
        )
      )
      current = numericRepaired
    }

    return current.replace(Regex("""\s{2,}"""), " ").trim()
  }

  internal fun repairNumericAmountToken(rawToken: String): String {
    var repaired = rawToken
      .replace('O', '0')
      .replace('o', '0')
      .replace('l', '1')
      .replace('I', '1')
      .replace(Regex("""\s+"""), "")

    // Comma decimal ("51,78" -> "51.78")
    if (!repaired.contains('.') && repaired.count { it == ',' } == 1) {
      val commaParts = repaired.split(',')
      if (commaParts.size == 2 && commaParts[1].length == 2) {
        repaired = "${commaParts[0]}.${commaParts[1]}"
      }
    }

    // Double-dot thousands ("1.250.00" -> "1,250.00")
    val dotParts = repaired.split('.')
    if (dotParts.size >= 3 && dotParts.last().length == 2) {
      repaired = dotParts.dropLast(1).joinToString(",") + "." + dotParts.last()
    }

    return repaired
  }

  private fun isStatusPillToken(text: String): Boolean {
    val clean = text.trim().lowercase(Locale.US).trimEnd('✓', '✔', '!', '.')
    return clean in setOf(
      "paid", "settled", "completed", "upcoming", "unpaid",
      "pending", "overdue", "past due", "refunded", "reversed", "disputed"
    )
  }

  private data class LinePiece(
    val text: String,
    val box: OcrBoundingBox?,
    val sourceLine: OcrLine
  )

  private fun filterSystemChromeLines(lines: List<OcrLine>, pageHeight: Int): List<OcrLine> {
    if (pageHeight < 400 || lines.size < 4) return lines
    val topCutoff = (pageHeight * 0.048f).toInt()
    val bottomCutoff = (pageHeight * 0.952f).toInt()

    return lines.filter { line ->
      val box = line.box ?: return@filter true
      val inChromeBand = box.bottom <= topCutoff || box.top >= bottomCutoff
      if (!inChromeBand) return@filter true

      val trimmed = line.text.trim()
      if (financialGuardRegex.containsMatchIn(trimmed)) return@filter true

      val tokens = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
      val allChromeTokens = tokens.isNotEmpty() && tokens.all { token ->
        systemChromeClockOrSignalRegex.matches(token)
      }
      !allChromeTokens
    }
  }

  private fun splitWideMultiColumnLines(
    lines: List<OcrLine>,
    medianHeight: Int
  ): List<LinePiece> {
    val result = mutableListOf<LinePiece>()
    for (line in lines) {
      val elements = line.elements.filter { it.text.isNotBlank() && it.box != null }
      if (elements.size < 2) {
        result.add(LinePiece(line.text, line.box, line))
        continue
      }
      val sortedEls = elements.sortedBy { it.box!!.left }
      val subGroups = mutableListOf<MutableList<OcrElement>>()
      for (el in sortedEls) {
        val current = subGroups.lastOrNull()
        val prevBox = current?.lastOrNull()?.box
        val gap = if (prevBox != null && el.box != null) el.box.left - prevBox.right else 0
        if (current == null || gap > medianHeight * 1.15f) {
          subGroups.add(mutableListOf(el))
        } else {
          current.add(el)
        }
      }
      if (subGroups.size <= 1) {
        result.add(LinePiece(line.text, line.box, line))
      } else {
        for (group in subGroups) {
          val boxes = group.mapNotNull { it.box }
          val joinedText = group.joinToString(" ") { it.text.trim() }.trim()
          val box = if (boxes.isNotEmpty()) {
            OcrBoundingBox(
              left = boxes.minOf { it.left },
              top = boxes.minOf { it.top },
              right = boxes.maxOf { it.right },
              bottom = boxes.maxOf { it.bottom }
            )
          } else line.box
          result.add(LinePiece(joinedText, box, line))
        }
      }
    }
    return result
  }

  private fun stripLeadingStandaloneEmblemCells(
    cells: List<OcrCell>,
    screenshotIndex: Int,
    audits: MutableList<TextCorrectionAudit>
  ): List<OcrCell> {
    if (cells.size < 2) return cells
    val firstText = cells.first().text.trim()
    val secondText = cells[1].text.trim()
    val looksFollowedByTitleOrMoney = secondText.startsWith("₱") ||
      secondText.startsWith("P", ignoreCase = true) ||
      secondText.any { it.isLetter() }
    if (standaloneEmblemTokenRegex.matches(firstText) && looksFollowedByTitleOrMoney) {
      val remainingJoined = cells.drop(1).joinToString(" ") { it.text.trim() }.trim()
      audits.add(
        TextCorrectionAudit(
          rawText = cells.joinToString(" ") { it.text.trim() },
          normalizedText = remainingJoined,
          reason = "Removed circular app-icon OCR noise",
          screenshotIndex = screenshotIndex
        )
      )
      return cells.drop(1)
    }
    return cells
  }

  private fun mergeAdjacentCurrencyCells(cells: List<OcrCell>, medianHeight: Int): List<OcrCell> {
    if (cells.size < 2) return cells
    val result = mutableListOf<OcrCell>()
    var i = 0
    while (i < cells.size) {
      val curr = cells[i]
      val next = cells.getOrNull(i + 1)
      if (next != null &&
        standaloneCurrencyPrefixRegex.matches(curr.text.trim()) &&
        startsWithMoneyDigitsRegex.containsMatchIn(next.text.trim()) &&
        (next.left - curr.right) <= medianHeight * 3.5f
      ) {
        val rawPrefix = curr.text.trim()
        val prefix = if (rawPrefix.equals("PHP", ignoreCase = true)) "PHP" else "₱"
        val numText = repairNumericAmountToken(next.text.trim())
        val mergedText = if (prefix == "₱") "$prefix$numText" else "$prefix $numText"
        result.add(
          OcrCell(
            text = mergedText,
            left = min(curr.left, next.left),
            top = min(curr.top, next.top),
            right = max(curr.right, next.right),
            bottom = max(curr.bottom, next.bottom),
            confidence = averageConfidences(listOfNotNull(curr.confidence, next.confidence)),
            blockId = curr.blockId ?: next.blockId
          )
        )
        i += 2
      } else {
        result.add(curr)
        i++
      }
    }
    return result
  }

  private fun mergeStandaloneCurrencyColumns(cols: List<String>): List<String> {
    if (cols.size < 2) return cols
    val out = mutableListOf<String>()
    var i = 0
    while (i < cols.size) {
      val curr = cols[i]
      val next = cols.getOrNull(i + 1)
      if (next != null &&
        standaloneCurrencyPrefixRegex.matches(curr) &&
        startsWithMoneyDigitsRegex.containsMatchIn(next)
      ) {
        val prefix = if (curr.equals("PHP", ignoreCase = true)) "PHP " else "₱"
        out.add("$prefix${repairNumericAmountToken(next)}")
        i += 2
      } else {
        out.add(curr)
        i++
      }
    }
    return out
  }

  private fun averageConfidences(values: List<Float>): Float? =
    if (values.isEmpty()) null else (values.sum() / values.size.toFloat()).coerceIn(0f, 1f)

  internal fun sanitizeOcrGlyphs(raw: String): String =
    raw
      .replace('\u00A0', ' ')
      .replace('\u2007', ' ')
      .replace('\u2009', ' ')
      .replace('\u202F', ' ')
      .replace('\u0131', 'i')
      .replace('\u0130', 'I')
      .replace('\u2013', '-')
      .replace('\u2014', '-')
      .replace('\u2212', '-')
}
