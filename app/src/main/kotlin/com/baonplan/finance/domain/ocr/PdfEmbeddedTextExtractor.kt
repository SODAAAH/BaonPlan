package com.baonplan.finance.domain.ocr

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToInt

data class ExtractedPdfPageText(
  val pageNumber: Int,
  val totalPages: Int,
  val hasSelectableText: Boolean,
  val lines: List<OcrLine>,
  val rawText: String
)

sealed interface PdfTextExtractionOutcome {
  data class Success(
    val pages: List<ExtractedPdfPageText>,
    val totalPages: Int
  ) : PdfTextExtractionOutcome

  data class PasswordProtected(val userMessage: String) : PdfTextExtractionOutcome
  data class CorruptedOrUnsupported(val userMessage: String) : PdfTextExtractionOutcome
}

/**
 * Pure JVM-testable PDF embedded-text and table-coordinate reader.
 * Extracts selectable text, FlateDecode content streams, page boundaries, and text matrix
 * coordinates (Tm / Td / TD) so multi-page Loan Agreements, Disclosure Statements, and
 * Amortization Schedules retain row/column layout without external PDF libraries.
 * Flags image-only/scanned pages so the Android layer can run on-device PdfRenderer + ML Kit OCR.
 */
object PdfEmbeddedTextExtractor {

  private const val MAX_PAGES_TO_PARSE = 25
  private const val MIN_SELECTABLE_ALNUM_PER_PAGE = 24

  private val pdfHeaderRegex = Regex("""%PDF-\d\.\d""")
  private val encryptDictRegex = Regex("""/Encrypt\b""")
  private val objHeaderRegex = Regex("""(\d+)\s+(\d+)\s+obj\b""")
  private val streamStartRegex = Regex("""stream\r?\n""")
  private val refRegex = Regex("""(\d+)\s+\d+\s+R\b""")
  private val textMatrixRegex = Regex(
    """([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s+Tm\b"""
  )
  private val textMoveRegex = Regex(
    """([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s+T[dD]\b"""
  )

  private data class PdfIndirectObject(
    val objNum: Int,
    val genNum: Int,
    val dictText: String,
    val streamBytes: ByteArray?
  )

  private data class PositionedTextRun(
    val text: String,
    val x: Float,
    val y: Float
  )

  fun extractFromBytes(pdfBytes: ByteArray): PdfTextExtractionOutcome {
    if (pdfBytes.size < 16) {
      return PdfTextExtractionOutcome.CorruptedOrUnsupported(
        "The selected PDF file is empty or corrupted. Try another file or upload screenshots."
      )
    }

    val latin = String(pdfBytes, StandardCharsets.ISO_8859_1)
    val headerWindow = latin.take(1024)
    if (!pdfHeaderRegex.containsMatchIn(headerWindow)) {
      return PdfTextExtractionOutcome.CorruptedOrUnsupported(
        "The selected file is not a valid PDF document. Upload a valid PDF or screenshots instead."
      )
    }

    if (encryptDictRegex.containsMatchIn(latin)) {
      return PdfTextExtractionOutcome.PasswordProtected(
        "This PDF is password-protected or encrypted. Export an unlocked PDF or upload screenshots of its pages."
      )
    }

    val objects = parseIndirectObjects(pdfBytes, latin)
    if (objects.isEmpty()) {
      return PdfTextExtractionOutcome.CorruptedOrUnsupported(
        "Could not read internal structure of this PDF. Try uploading screenshots of its pages."
      )
    }

    val leafPageObjs = collectOrderedLeafPages(objects)
    if (leafPageObjs.isEmpty()) {
      // Fallback: inspect any stream containing BT ... ET operators
      val fallbackRuns = objects.values
        .mapNotNull { it.streamBytes?.let { raw -> decodeStreamBytes(it.dictText, raw) } }
        .filter { it.contains("BT") && it.contains("ET") }
      if (fallbackRuns.isEmpty()) {
        return PdfTextExtractionOutcome.Success(
          pages = listOf(
            ExtractedPdfPageText(
              pageNumber = 1,
              totalPages = 1,
              hasSelectableText = false,
              lines = emptyList(),
              rawText = ""
            )
          ),
          totalPages = 1
        )
      }
      val lines = parseContentStreamToLines(fallbackRuns.joinToString("\n"))
      val rawText = lines.joinToString("\n") { it.text }
      val alnum = rawText.count { it.isLetterOrDigit() }
      return PdfTextExtractionOutcome.Success(
        pages = listOf(
          ExtractedPdfPageText(
            pageNumber = 1,
            totalPages = 1,
            hasSelectableText = alnum >= MIN_SELECTABLE_ALNUM_PER_PAGE,
            lines = lines,
            rawText = rawText
          )
        ),
        totalPages = 1
      )
    }

    val boundedPages = leafPageObjs.take(MAX_PAGES_TO_PARSE)
    val totalPages = leafPageObjs.size
    val extractedPages = boundedPages.mapIndexed { idx, pageObj ->
      val contentStreams = resolvePageContentStreams(pageObj, objects)
      val combinedStreamText = contentStreams.joinToString("\n")
      val lines = if (combinedStreamText.isNotBlank()) {
        parseContentStreamToLines(combinedStreamText)
      } else {
        emptyList()
      }
      val rawText = lines.joinToString("\n") { it.text }
      val alnum = rawText.count { it.isLetterOrDigit() }
      ExtractedPdfPageText(
        pageNumber = idx + 1,
        totalPages = totalPages,
        hasSelectableText = alnum >= MIN_SELECTABLE_ALNUM_PER_PAGE && lines.isNotEmpty(),
        lines = lines,
        rawText = rawText
      )
    }

    return PdfTextExtractionOutcome.Success(
      pages = extractedPages,
      totalPages = totalPages
    )
  }

  private fun parseIndirectObjects(
    pdfBytes: ByteArray,
    latin: String
  ): Map<Int, PdfIndirectObject> {
    val result = LinkedHashMap<Int, PdfIndirectObject>()
    val matches = objHeaderRegex.findAll(latin).toList()
    for (i in matches.indices) {
      val m = matches[i]
      val objNum = m.groupValues[1].toIntOrNull() ?: continue
      val genNum = m.groupValues[2].toIntOrNull() ?: 0
      val bodyStart = m.range.last + 1
      val nextObjStart = if (i + 1 < matches.size) matches[i + 1].range.first else latin.length
      val endObjIdx = latin.indexOf("endobj", bodyStart).takeIf { it in bodyStart..nextObjStart } ?: nextObjStart
      if (endObjIdx <= bodyStart) continue

      val bodySlice = latin.substring(bodyStart, endObjIdx)
      val streamMatch = streamStartRegex.find(bodySlice)
      if (streamMatch != null) {
        val dictText = bodySlice.substring(0, streamMatch.range.first).trim()
        val rawStreamStart = bodyStart + streamMatch.range.last + 1
        val endStreamRel = bodySlice.indexOf("endstream", streamMatch.range.last + 1)
        if (endStreamRel > streamMatch.range.last) {
          var rawStreamEnd = bodyStart + endStreamRel
          if (rawStreamEnd > rawStreamStart && pdfBytes[rawStreamEnd - 1] == '\n'.code.toByte()) {
            rawStreamEnd--
          }
          if (rawStreamEnd > rawStreamStart && pdfBytes[rawStreamEnd - 1] == '\r'.code.toByte()) {
            rawStreamEnd--
          }
          val bytes = if (rawStreamEnd > rawStreamStart && rawStreamEnd <= pdfBytes.size) {
            pdfBytes.copyOfRange(rawStreamStart, rawStreamEnd)
          } else null
          result[objNum] = PdfIndirectObject(objNum, genNum, dictText, bytes)
        } else {
          result[objNum] = PdfIndirectObject(objNum, genNum, dictText, null)
        }
      } else {
        result[objNum] = PdfIndirectObject(objNum, genNum, bodySlice.trim(), null)
      }
    }
    return result
  }

  private fun collectOrderedLeafPages(objects: Map<Int, PdfIndirectObject>): List<PdfIndirectObject> {
    val catalog = objects.values.firstOrNull {
      Regex("""/Type\s*/Catalog\b""").containsMatchIn(it.dictText)
    }
    val rootPagesRef = catalog?.let {
      Regex("""/Pages\s+(\d+)\s+\d+\s+R\b""").find(it.dictText)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    val visited = HashSet<Int>()
    val ordered = mutableListOf<PdfIndirectObject>()

    fun traverse(nodeId: Int) {
      if (!visited.add(nodeId) || ordered.size >= MAX_PAGES_TO_PARSE) return
      val obj = objects[nodeId] ?: return
      val isLeafPage = Regex("""/Type\s*/Page\b""").containsMatchIn(obj.dictText) &&
        !Regex("""/Type\s*/Pages\b""").containsMatchIn(obj.dictText)
      if (isLeafPage) {
        ordered.add(obj)
        return
      }
      val kidsSection = Regex("""/Kids\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL))
        .find(obj.dictText)?.groupValues?.getOrNull(1)
      if (!kidsSection.isNullOrBlank()) {
        for (ref in refRegex.findAll(kidsSection)) {
          val childId = ref.groupValues[1].toIntOrNull() ?: continue
          traverse(childId)
        }
      }
    }

    if (rootPagesRef != null) {
      traverse(rootPagesRef)
    }
    if (ordered.isEmpty()) {
      objects.values
        .filter {
          Regex("""/Type\s*/Page\b""").containsMatchIn(it.dictText) &&
            !Regex("""/Type\s*/Pages\b""").containsMatchIn(it.dictText)
        }
        .sortedBy { it.objNum }
        .forEach { ordered.add(it) }
    }
    return ordered
  }

  private fun resolvePageContentStreams(
    pageObj: PdfIndirectObject,
    objects: Map<Int, PdfIndirectObject>
  ): List<String> {
    val dict = pageObj.dictText
    val arrayMatch = Regex("""/Contents\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL)).find(dict)
    val contentObjIds = if (arrayMatch != null) {
      refRegex.findAll(arrayMatch.groupValues[1]).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
    } else {
      val singleRef = Regex("""/Contents\s+(\d+)\s+\d+\s+R\b""").find(dict)?.groupValues?.getOrNull(1)?.toIntOrNull()
      listOfNotNull(singleRef)
    }

    val streams = mutableListOf<String>()
    for (id in contentObjIds) {
      val streamObj = objects[id] ?: continue
      val rawBytes = streamObj.streamBytes ?: continue
      val decoded = decodeStreamBytes(streamObj.dictText, rawBytes)
      if (!decoded.isNullOrBlank()) {
        streams.add(decoded)
      }
    }
    if (streams.isEmpty() && pageObj.streamBytes != null) {
      decodeStreamBytes(pageObj.dictText, pageObj.streamBytes)?.let { streams.add(it) }
    }
    return streams
  }

  private fun decodeStreamBytes(dictText: String, rawBytes: ByteArray): String? {
    val isFlate = Regex("""/Filter\s*(?:\[[^\]]*)?/FlateDecode\b""").containsMatchIn(dictText)
    val inflated = if (isFlate) {
      inflateZlib(rawBytes) ?: return null
    } else {
      rawBytes
    }
    return String(inflated, StandardCharsets.ISO_8859_1)
  }

  private fun inflateZlib(compressed: ByteArray): ByteArray? = runCatching {
    val inflater = Inflater()
    try {
      inflater.setInput(compressed)
      val out = ByteArrayOutputStream(compressed.size * 2)
      val buffer = ByteArray(4096)
      var total = 0
      while (!inflater.finished() && total < 4_000_000) {
        val count = inflater.inflate(buffer)
        if (count == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) break
        } else {
          out.write(buffer, 0, count)
          total += count
        }
      }
      out.toByteArray()
    } finally {
      inflater.end()
    }
  }.getOrNull()

  internal fun parseContentStreamToLines(streamText: String): List<OcrLine> {
    val runs = mutableListOf<PositionedTextRun>()
    var curX = 72f
    var curY = 720f
    var lineStartX = 72f
    var leading = 14f

    val btBlocks = Regex("""\bBT\b(.*?)\bET\b""", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(streamText)
    for (block in btBlocks) {
      val content = block.groupValues[1]
      val lines = content.lines()
      for (rawOpLine in lines) {
        val op = rawOpLine.trim()
        if (op.isEmpty()) continue

        textMatrixRegex.find(op)?.let { tm ->
          val e = tm.groupValues[5].toFloatOrNull()
          val f = tm.groupValues[6].toFloatOrNull()
          if (e != null && f != null) {
            curX = e
            curY = f
            lineStartX = e
          }
        }

        textMoveRegex.find(op)?.let { td ->
          val tx = td.groupValues[1].toFloatOrNull() ?: 0f
          val ty = td.groupValues[2].toFloatOrNull() ?: 0f
          curX = lineStartX + tx
          curY += ty
          lineStartX = curX
          if (op.endsWith("TD") && ty != 0f) {
            leading = abs(ty)
          }
        }

        if (op == "T*" || op.endsWith(" T*")) {
          curX = lineStartX
          curY -= leading
        }

        // Extract text from TJ array: [(Hello) -20 (World)] TJ
        if (op.contains("TJ")) {
          val bracketStart = op.indexOf('[')
          val bracketEnd = op.lastIndexOf(']')
          if (bracketStart >= 0 && bracketEnd > bracketStart) {
            val arrayBody = op.substring(bracketStart + 1, bracketEnd)
            val text = extractTextFromTjArray(arrayBody)
            if (text.isNotBlank()) {
              runs.add(PositionedTextRun(text = text, x = curX, y = curY))
              curX += text.length * 5.2f
            }
          }
        } else if (op.endsWith("Tj") || op.endsWith("'") || op.endsWith("\"")) {
          if (op.endsWith("'") || op.endsWith("\"")) {
            curX = lineStartX
            curY -= leading
          }
          val text = extractSinglePdfString(op)
          if (text.isNotBlank()) {
            runs.add(PositionedTextRun(text = text, x = curX, y = curY))
            curX += text.length * 5.2f
          }
        }
      }
    }

    if (runs.isEmpty()) return emptyList()

    // Group runs sharing similar PDF Y coordinate into visual rows (PDF Y increases upward)
    val sortedRuns = runs.sortedWith(compareByDescending<PositionedTextRun> { it.y }.thenBy { it.x })
    val groupedRows = mutableListOf<MutableList<PositionedTextRun>>()
    for (run in sortedRuns) {
      val targetRow = groupedRows.firstOrNull { row ->
        abs(row.first().y - run.y) <= 4.5f
      }
      if (targetRow != null) {
        targetRow.add(run)
      } else {
        groupedRows.add(mutableListOf(run))
      }
    }

    val maxY = groupedRows.maxOfOrNull { it.first().y } ?: 792f
    return groupedRows.mapIndexedNotNull { rowIdx, rowRuns ->
      val ordered = rowRuns.sortedBy { it.x }
      val sb = StringBuilder()
      var prevEndX: Float? = null
      for (r in ordered) {
        val clean = r.text.trim()
        if (clean.isEmpty()) continue
        if (prevEndX != null) {
          val gap = r.x - prevEndX
          if (gap > 18f) {
            sb.append("   ")
          } else if (gap > 2.5f && !sb.endsWith(" ")) {
            sb.append(' ')
          }
        }
        sb.append(clean)
        prevEndX = r.x + clean.length * 5.2f
      }
      val rowText = sb.toString().trim()
      if (rowText.isEmpty()) return@mapIndexedNotNull null

      val leftPx = (ordered.first().x * 2f).roundToInt().coerceAtLeast(10)
      val rightPx = ((ordered.last().x + ordered.last().text.length * 5.5f) * 2f)
        .roundToInt()
        .coerceAtLeast(leftPx + 20)
      val topPx = (((maxY - ordered.first().y) * 2f) + 24f).roundToInt().coerceAtLeast(10)
      val elements = ordered.map { r ->
        val elLeft = (r.x * 2f).roundToInt().coerceAtLeast(10)
        val elRight = ((r.x + r.text.length * 5.5f) * 2f).roundToInt().coerceAtLeast(elLeft + 10)
        OcrElement(
          text = r.text.trim(),
          box = OcrBoundingBox(left = elLeft, top = topPx, right = elRight, bottom = topPx + 22),
          confidence = 0.99f
        )
      }
      OcrLine(
        text = rowText,
        box = OcrBoundingBox(left = leftPx, top = topPx, right = rightPx, bottom = topPx + 22),
        confidence = 0.99f,
        blockId = rowIdx,
        elements = elements
      )
    }
  }

  private fun extractTextFromTjArray(arrayBody: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < arrayBody.length) {
      when (val ch = arrayBody[i]) {
        '(' -> {
          val (decoded, nextIdx) = readLiteralString(arrayBody, i)
          sb.append(decoded)
          i = nextIdx
        }
        '<' -> {
          if (i + 1 < arrayBody.length && arrayBody[i + 1] != '<') {
            val closeIdx = arrayBody.indexOf('>', i + 1)
            if (closeIdx > i) {
              sb.append(decodeHexString(arrayBody.substring(i + 1, closeIdx)))
              i = closeIdx + 1
            } else {
              i++
            }
          } else {
            i++
          }
        }
        '-', in '0'..'9' -> {
          var end = i + 1
          while (end < arrayBody.length && (arrayBody[end].isDigit() || arrayBody[end] == '.')) {
            end++
          }
          val kerning = arrayBody.substring(i, end).toFloatOrNull() ?: 0f
          if (kerning <= -140f && sb.isNotEmpty() && !sb.endsWith(" ")) {
            sb.append(' ')
          }
          i = end
        }
        else -> i++
      }
    }
    return normalizeDecodedPdfText(sb.toString())
  }

  private fun extractSinglePdfString(op: String): String {
    val parenIdx = op.indexOf('(')
    val hexIdx = op.indexOf('<')
    return when {
      parenIdx >= 0 && (hexIdx < 0 || parenIdx < hexIdx) -> {
        val (decoded, _) = readLiteralString(op, parenIdx)
        normalizeDecodedPdfText(decoded)
      }
      hexIdx >= 0 -> {
        val closeIdx = op.indexOf('>', hexIdx + 1)
        if (closeIdx > hexIdx) {
          normalizeDecodedPdfText(decodeHexString(op.substring(hexIdx + 1, closeIdx)))
        } else ""
      }
      else -> ""
    }
  }

  private fun readLiteralString(src: String, openParenIdx: Int): Pair<String, Int> {
    val sb = StringBuilder()
    var depth = 0
    var i = openParenIdx
    while (i < src.length) {
      val c = src[i]
      if (c == '\\' && i + 1 < src.length) {
        val next = src[i + 1]
        when (next) {
          'n' -> { sb.append(' '); i += 2 }
          'r' -> { sb.append(' '); i += 2 }
          't' -> { sb.append("  "); i += 2 }
          '(', ')', '\\' -> { sb.append(next); i += 2 }
          in '0'..'7' -> {
            var octEnd = i + 2
            while (octEnd < src.length && octEnd < i + 4 && src[octEnd] in '0'..'7') octEnd++
            val code = src.substring(i + 1, octEnd).toIntOrNull(8) ?: 32
            sb.append(mapPdfCharCode(code))
            i = octEnd
          }
          else -> { sb.append(next); i += 2 }
        }
      } else if (c == '(') {
        if (depth > 0) sb.append('(')
        depth++
        i++
      } else if (c == ')') {
        depth--
        i++
        if (depth <= 0) break
        sb.append(')')
      } else {
        sb.append(c)
        i++
      }
    }
    return sb.toString() to i
  }

  private fun decodeHexString(hexRaw: String): String {
    val clean = hexRaw.filter { !it.isWhitespace() }
    if (clean.isEmpty()) return ""
    val padded = if (clean.length % 2 != 0) "${clean}0" else clean
    val bytes = ByteArray(padded.length / 2) { idx ->
      padded.substring(idx * 2, idx * 2 + 2).toIntOrNull(16)?.toByte() ?: 0x20
    }
    // Check UTF-16BE BOM (FE FF)
    if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
      return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
    }
    val sb = StringBuilder()
    for (b in bytes) {
      sb.append(mapPdfCharCode(b.toInt() and 0xFF))
    }
    return sb.toString()
  }

  private fun mapPdfCharCode(code: Int): Char = when (code) {
    0x20AC -> '₱'
    in 0x20..0x7E -> code.toChar()
    0xA0 -> ' '
    in 0xA1..0xFF -> code.toChar()
    else -> ' '
  }

  private fun normalizeDecodedPdfText(raw: String): String =
    raw.replace("\u0000", "")
      .replace(Regex("""\bPHP\s*(?=\d)""", RegexOption.IGNORE_CASE), "₱")
      .replace(Regex("""\s+"""), " ")
      .trim()
}
