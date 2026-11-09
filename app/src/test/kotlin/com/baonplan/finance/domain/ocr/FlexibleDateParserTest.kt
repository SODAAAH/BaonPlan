package com.baonplan.finance.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FlexibleDateParserTest {

  private val refDate = LocalDate.of(2026, 11, 10)

  @Test
  fun parsesStandardIsoAndNamedMonthFormatsWithHighConfidence() {
    val iso = FlexibleDateParser.parseSingleDate("Due on 2026-11-16", referenceDate = refDate)
    assertNotNull(iso)
    assertEquals("2026-11-16", iso!!.isoDate)
    assertEquals(ExtractionConfidence.HIGH, iso.confidence)

    val monthFirst = FlexibleDateParser.parseSingleDate("Next payment: Nov 16, 2026", referenceDate = refDate)
    assertNotNull(monthFirst)
    assertEquals("2026-11-16", monthFirst!!.isoDate)
    assertEquals(ExtractionConfidence.HIGH, monthFirst.confidence)

    val dayFirstOrdinal = FlexibleDateParser.parseSingleDate("16th November, 2026", referenceDate = refDate)
    assertNotNull(dayFirstOrdinal)
    assertEquals("2026-11-16", dayFirstOrdinal!!.isoDate)
    assertEquals(ExtractionConfidence.HIGH, dayFirstOrdinal.confidence)

    val hyphenDayFirst = FlexibleDateParser.parseSingleDate("05-Jan-2027", referenceDate = refDate)
    assertNotNull(hyphenDayFirst)
    assertEquals("2027-01-05", hyphenDayFirst!!.isoDate)
    assertEquals(ExtractionConfidence.HIGH, hyphenDayFirst.confidence)

    // Tight OCR comma/space variants (e.g. missing space after comma)
    val tightComma = FlexibleDateParser.parseSingleDate("Feb 27,2027", referenceDate = refDate)
    assertNotNull(tightComma)
    assertEquals("2027-02-27", tightComma!!.isoDate)
    assertEquals(ExtractionConfidence.HIGH, tightComma.confidence)

    val noSpaceMonthDay = FlexibleDateParser.parseSingleDate("Feb27,2027", referenceDate = refDate)
    assertNotNull(noSpaceMonthDay)
    assertEquals("2027-02-27", noSpaceMonthDay!!.isoDate)

    val dayFirstTightComma = FlexibleDateParser.parseSingleDate("27 Feb,2027", referenceDate = refDate)
    assertNotNull(dayFirstTightComma)
    assertEquals("2027-02-27", dayFirstTightComma!!.isoDate)

    val periodBeforeYear = FlexibleDateParser.parseSingleDate("Feb 27. 2027", referenceDate = refDate)
    assertNotNull(periodBeforeYear)
    assertEquals("2027-02-27", periodBeforeYear!!.isoDate)
  }

  @Test
  fun distinguishesMonthFirstAndDayFirstNumericDatesAndFlagsAmbiguity() {
    val linesDayFirst = listOf("16/11/2026 ₱500.00", "04/05/2026 ₱500.00")
    assertEquals(NumericDateOrder.DAY_FIRST, FlexibleDateParser.inferNumericOrder(linesDayFirst))

    val unambiguous = FlexibleDateParser.parseSingleDate(
      "16/11/2026",
      numericOrder = NumericDateOrder.DAY_FIRST,
      referenceDate = refDate
    )
    assertNotNull(unambiguous)
    assertEquals("2026-11-16", unambiguous!!.isoDate)
    assertEquals(ExtractionConfidence.HIGH, unambiguous.confidence)

    val resolvedAmbiguous = FlexibleDateParser.parseSingleDate(
      "04/05/2026",
      numericOrder = NumericDateOrder.DAY_FIRST,
      referenceDate = refDate
    )
    assertNotNull(resolvedAmbiguous)
    assertEquals("2026-05-04", resolvedAmbiguous!!.isoDate)
    assertEquals(ExtractionConfidence.NEEDS_VERIFICATION, resolvedAmbiguous.confidence)

    val pureAmbiguous = FlexibleDateParser.parseSingleDate(
      "04/05/2026",
      numericOrder = NumericDateOrder.AMBIGUOUS,
      referenceDate = refDate
    )
    assertNotNull(pureAmbiguous)
    assertEquals(ExtractionConfidence.NEEDS_VERIFICATION, pureAmbiguous!!.confidence)
    assertNotNull(pureAmbiguous.note)
  }

  @Test
  fun yearlessDatesAlwaysRequireVerificationNeverMarkedHigh() {
    val yearless = FlexibleDateParser.parseSingleDate(
      "Nov 16",
      contextYear = 2026,
      referenceDate = refDate
    )
    assertNotNull(yearless)
    assertEquals("2026-11-16", yearless!!.isoDate)
    assertTrue(yearless.isYearInferred)
    assertEquals(ExtractionConfidence.NEEDS_VERIFICATION, yearless.confidence)

    val dayMonthYearless = FlexibleDateParser.parseSingleDate(
      "19 Mar",
      contextYear = null,
      referenceDate = LocalDate.of(2026, 1, 5)
    )
    assertNotNull(dayMonthYearless)
    assertEquals("2026-03-19", dayMonthYearless!!.isoDate)
    assertEquals(ExtractionConfidence.NEEDS_VERIFICATION, dayMonthYearless.confidence)
  }

  @Test
  fun rejectsImpossibleCalendarDatesCleanly() {
    assertNull(FlexibleDateParser.parseSingleDate("Feb 30, 2026", referenceDate = refDate))
    assertNull(FlexibleDateParser.parseSingleDate("2026-02-30", referenceDate = refDate))
    assertNull(FlexibleDateParser.parseSingleDate("Nov 31, 2026", referenceDate = refDate))
    assertNull(FlexibleDateParser.parseSingleDate("2025-02-29", referenceDate = refDate))
    assertNotNull(FlexibleDateParser.parseSingleDate("2024-02-29", referenceDate = refDate))
  }
}
