package com.baonplan.finance.domain.ocr

import com.baonplan.finance.domain.util.ManilaTime
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.util.Locale

data class ParsedDateMatch(
  val isoDate: String,
  val rawText: String,
  val confidence: ExtractionConfidence,
  val isYearInferred: Boolean = false,
  val isAmbiguousNumericOrder: Boolean = false,
  val note: String? = null
)

enum class NumericDateOrder {
  MONTH_FIRST,
  DAY_FIRST,
  AMBIGUOUS
}

/**
 * Pure Kotlin multi-format date parser tailored to Philippine lender screenshots.
 * Never fabricates impossible calendar dates; flags yearless or ambiguous numeric dates.
 */
object FlexibleDateParser {

  private val monthMap: Map<String, Int> = mapOf(
    "jan" to 1, "january" to 1,
    "feb" to 2, "february" to 2,
    "mar" to 3, "march" to 3,
    "apr" to 4, "april" to 4,
    "may" to 5,
    "jun" to 6, "june" to 6,
    "jul" to 7, "july" to 7,
    "aug" to 8, "august" to 8,
    "sep" to 9, "sept" to 9, "september" to 9,
    "oct" to 10, "october" to 10,
    "nov" to 11, "november" to 11,
    "dec" to 12, "december" to 12
  )

  // "2026-11-16", "2026/11/16", "2026.11.16"
  private val isoPattern = Regex("""\b(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})\b""")

  // "Nov 16, 2026" | "Feb 27,2027" | "November 16th 2026" | "Nov. 16, '26" | "Nov16, 2026"
  private val monthFirstNamedWithYear = Regex(
    """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:[.\-\s]+|(?=\d))(\d{1,2})(?:st|nd|rd|th)?(?:[,\.\-/]\s*|\s+)(20\d{2}|'\d{2})\b""",
    RegexOption.IGNORE_CASE
  )

  // "16 Nov 2026" | "16-Nov-2026" | "16 Nov,2026" | "16th November, '26"
  private val dayFirstNamedWithYear = Regex(
    """\b(\d{1,2})(?:st|nd|rd|th)?(?:[\s\-/]+|(?=[A-Za-z]))(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?(?:[,\.\-/]\s*|\s+)(20\d{2}|'\d{2})\b""",
    RegexOption.IGNORE_CASE
  )

  // "11/16/2026" | "16/11/2026" | "11-16-26" | "11.16.2026"
  private val slashedNumericPattern = Regex("""\b(\d{1,2})([/\-.])(\d{1,2})\2(20\d{2}|\d{2})\b""")

  // Yearless: "Nov 16" | "16 Nov" (parsed cautiously with MEDIUM confidence)
  private val monthFirstYearless = Regex(
    """\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?:\.?\s+|(?=\d))(\d{1,2})(?:st|nd|rd|th)?\b(?![,\s\-']+\d{2,4})""",
    RegexOption.IGNORE_CASE
  )

  private val dayFirstYearless = Regex(
    """\b(\d{1,2})(?:st|nd|rd|th)?(?:[\s\-]+|(?=[A-Za-z]))(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\b(?![,\s\-']+\d{2,4})""",
    RegexOption.IGNORE_CASE
  )

  private val yearPattern = Regex("""\b(20\d{2})\b""")

  fun extractDocumentYears(lines: List<String>): Set<Int> =
    lines.flatMap { line ->
      yearPattern.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }
    }.toSet()

  fun inferNumericOrder(lines: List<String>): NumericDateOrder {
    var hasFirstGt12 = false
    var hasSecondGt12 = false
    for (line in lines) {
      for (match in slashedNumericPattern.findAll(line)) {
        val a = match.groupValues[1].toIntOrNull() ?: continue
        val b = match.groupValues[3].toIntOrNull() ?: continue
        if (a in 13..31 && b in 1..12) hasFirstGt12 = true
        if (b in 13..31 && a in 1..12) hasSecondGt12 = true
      }
    }
    return when {
      hasSecondGt12 && !hasFirstGt12 -> NumericDateOrder.MONTH_FIRST
      hasFirstGt12 && !hasSecondGt12 -> NumericDateOrder.DAY_FIRST
      else -> NumericDateOrder.AMBIGUOUS
    }
  }

  fun parseAllDatesInText(
    text: String,
    numericOrder: NumericDateOrder = NumericDateOrder.AMBIGUOUS,
    contextYear: Int? = null,
    referenceDate: LocalDate = ManilaTime.today()
  ): List<ParsedDateMatch> {
    if (text.isBlank()) return emptyList()

    val found = mutableListOf<Pair<IntRange, ParsedDateMatch>>()

    fun overlapsExisting(range: IntRange): Boolean =
      found.any { (existing, _) -> range.first <= existing.last && existing.first <= range.last }

    // 1. ISO yyyy-MM-dd
    for (m in isoPattern.findAll(text)) {
      val date = safeLocalDate(
        m.groupValues[1].toIntOrNull(),
        m.groupValues[2].toIntOrNull(),
        m.groupValues[3].toIntOrNull()
      ) ?: continue
      found.add(
        m.range to ParsedDateMatch(
          isoDate = ManilaTime.formatIso(date),
          rawText = m.value,
          confidence = ExtractionConfidence.HIGH
        )
      )
    }

    // 2. Named month + day + year
    for (m in monthFirstNamedWithYear.findAll(text)) {
      if (overlapsExisting(m.range)) continue
      val month = monthMap[m.groupValues[1].lowercase(Locale.US)] ?: continue
      val day = m.groupValues[2].toIntOrNull() ?: continue
      val rawYr = m.groupValues[3].removePrefix("'")
      val yearNum = rawYr.toIntOrNull() ?: continue
      val isShortYr = rawYr.length == 2
      val year = if (isShortYr) 2000 + yearNum else yearNum
      val date = safeLocalDate(year, month, day) ?: continue
      found.add(
        m.range to ParsedDateMatch(
          isoDate = ManilaTime.formatIso(date),
          rawText = m.value,
          confidence = if (isShortYr) ExtractionConfidence.MEDIUM else ExtractionConfidence.HIGH,
          note = if (isShortYr) "Short year in \"${m.value}\" — verify" else null
        )
      )
    }

    // 3. Day + named month + year
    for (m in dayFirstNamedWithYear.findAll(text)) {
      if (overlapsExisting(m.range)) continue
      val day = m.groupValues[1].toIntOrNull() ?: continue
      val month = monthMap[m.groupValues[2].lowercase(Locale.US)] ?: continue
      val rawYr = m.groupValues[3].removePrefix("'")
      val yearNum = rawYr.toIntOrNull() ?: continue
      val isShortYr = rawYr.length == 2
      val year = if (isShortYr) 2000 + yearNum else yearNum
      val date = safeLocalDate(year, month, day) ?: continue
      found.add(
        m.range to ParsedDateMatch(
          isoDate = ManilaTime.formatIso(date),
          rawText = m.value,
          confidence = if (isShortYr) ExtractionConfidence.MEDIUM else ExtractionConfidence.HIGH,
          note = if (isShortYr) "Short year in \"${m.value}\" — verify" else null
        )
      )
    }

    // 4. Slashed / dashed / dotted numeric dates
    for (m in slashedNumericPattern.findAll(text)) {
      if (overlapsExisting(m.range)) continue
      val a = m.groupValues[1].toIntOrNull() ?: continue
      val b = m.groupValues[3].toIntOrNull() ?: continue
      val yearRaw = m.groupValues[4].toIntOrNull() ?: continue
      val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
      val isTwoDigitYear = m.groupValues[4].length == 2

      val resolved: Triple<LocalDate, ExtractionConfidence, Boolean>? = when {
        a in 1..12 && b in 13..31 -> safeLocalDate(year, a, b)?.let {
          Triple(it, if (isTwoDigitYear) ExtractionConfidence.MEDIUM else ExtractionConfidence.HIGH, false)
        }
        b in 1..12 && a in 13..31 -> safeLocalDate(year, b, a)?.let {
          Triple(it, if (isTwoDigitYear) ExtractionConfidence.MEDIUM else ExtractionConfidence.HIGH, false)
        }
        a in 1..12 && b in 1..12 -> {
          when (numericOrder) {
            NumericDateOrder.DAY_FIRST -> safeLocalDate(year, b, a)?.let {
              Triple(it, ExtractionConfidence.MEDIUM, a != b)
            }
            NumericDateOrder.MONTH_FIRST -> safeLocalDate(year, a, b)?.let {
              Triple(it, ExtractionConfidence.MEDIUM, a != b)
            }
            NumericDateOrder.AMBIGUOUS -> {
              if (a == b) {
                safeLocalDate(year, a, b)?.let {
                  Triple(it, if (isTwoDigitYear) ExtractionConfidence.MEDIUM else ExtractionConfidence.HIGH, false)
                }
              } else {
                safeLocalDate(year, a, b)?.let {
                  Triple(it, ExtractionConfidence.MEDIUM, true)
                }
              }
            }
          }
        }
        else -> null
      }
      if (resolved != null) {
        val (date, conf, ambiguousOrder) = resolved
        val note = when {
          ambiguousOrder -> "Numeric date \"${m.value}\" could be MM/DD or DD/MM"
          isTwoDigitYear -> "Two-digit year in \"${m.value}\" — verify"
          else -> null
        }
        found.add(
          m.range to ParsedDateMatch(
            isoDate = ManilaTime.formatIso(date),
            rawText = m.value,
            confidence = conf,
            isAmbiguousNumericOrder = ambiguousOrder,
            note = note
          )
        )
      }
    }

    // 5. Yearless month-day ("Nov 16" or "16 Nov") — never marked HIGH confidence
    for (m in monthFirstYearless.findAll(text)) {
      if (overlapsExisting(m.range)) continue
      val month = monthMap[m.groupValues[1].lowercase(Locale.US)] ?: continue
      val day = m.groupValues[2].toIntOrNull() ?: continue
      val inferredYear = resolveYearForMonthDay(month, day, contextYear, referenceDate) ?: continue
      val date = safeLocalDate(inferredYear, month, day) ?: continue
      found.add(
        m.range to ParsedDateMatch(
          isoDate = ManilaTime.formatIso(date),
          rawText = m.value,
          confidence = ExtractionConfidence.MEDIUM,
          isYearInferred = true,
          note = "Year not shown for \"${m.value}\" — inferred $inferredYear, please verify"
        )
      )
    }

    for (m in dayFirstYearless.findAll(text)) {
      if (overlapsExisting(m.range)) continue
      val day = m.groupValues[1].toIntOrNull() ?: continue
      val month = monthMap[m.groupValues[2].lowercase(Locale.US)] ?: continue
      val inferredYear = resolveYearForMonthDay(month, day, contextYear, referenceDate) ?: continue
      val date = safeLocalDate(inferredYear, month, day) ?: continue
      found.add(
        m.range to ParsedDateMatch(
          isoDate = ManilaTime.formatIso(date),
          rawText = m.value,
          confidence = ExtractionConfidence.MEDIUM,
          isYearInferred = true,
          note = "Year not shown for \"${m.value}\" — inferred $inferredYear, please verify"
        )
      )
    }

    return found.sortedBy { it.first.first }.map { it.second }
  }

  fun parseSingleDate(
    text: String,
    numericOrder: NumericDateOrder = NumericDateOrder.AMBIGUOUS,
    contextYear: Int? = null,
    referenceDate: LocalDate = ManilaTime.today()
  ): ParsedDateMatch? =
    parseAllDatesInText(text, numericOrder, contextYear, referenceDate).firstOrNull()

  private fun resolveYearForMonthDay(
    month: Int,
    day: Int,
    contextYear: Int?,
    referenceDate: LocalDate
  ): Int? {
    if (contextYear != null) {
      return if (safeLocalDate(contextYear, month, day) != null) contextYear else null
    }
    val baseYear = referenceDate.year
    val candidates = listOf(baseYear - 1, baseYear, baseYear + 1)
      .filter { y -> safeLocalDate(y, month, day) != null }
    if (candidates.isEmpty()) return null
    return candidates.minByOrNull { y ->
      val d = LocalDate.of(y, month, day)
      val days = java.time.temporal.ChronoUnit.DAYS.between(referenceDate, d)
      when {
        days in -90L..240L -> kotlin.math.abs(days)
        days > 240L -> days + 500L
        else -> kotlin.math.abs(days) + 1000L
      }
    }
  }

  private fun safeLocalDate(year: Int?, month: Int?, day: Int?): LocalDate? {
    if (year == null || month == null || day == null) return null
    if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) return null
    val maxDay = try {
      YearMonth.of(year, month).lengthOfMonth()
    } catch (_: Exception) {
      return null
    }
    if (day > maxDay) return null
    return if (Year.isLeap(year.toLong()) || !(month == 2 && day == 29)) {
      runCatching { LocalDate.of(year, month, day) }.getOrNull()
    } else null
  }
}
