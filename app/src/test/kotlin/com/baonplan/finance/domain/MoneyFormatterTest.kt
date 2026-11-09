package com.baonplan.finance.domain

import com.baonplan.finance.domain.util.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatterTest {

  @Test
  fun formatPeso_formatsExactCentavosWithCommasAndPesoSign() {
    assertEquals("₱0.00", MoneyFormatter.formatPeso(0L))
    assertEquals("₱26.15", MoneyFormatter.formatPeso(2_615L))
    assertEquals("₱2,096.17", MoneyFormatter.formatPeso(209_617L))
    assertEquals("-₱100.50", MoneyFormatter.formatPeso(-10_050L))
  }

  @Test
  fun formatPesoCompact_omitsZeroCentavosOnly() {
    assertEquals("₱400", MoneyFormatter.formatPesoCompact(40_000L))
    assertEquals("₱500", MoneyFormatter.formatPesoCompact(50_000L))
    assertEquals("₱335.07", MoneyFormatter.formatPesoCompact(33_507L))
  }

  @Test
  fun parsePesoToCents_parsesCleanlyWithoutFloatRoundingErrors() {
    assertEquals(209_617L, MoneyFormatter.parsePesoToCents("₱2,096.17"))
    assertEquals(40_000L, MoneyFormatter.parsePesoToCents("400"))
    assertEquals(2_615L, MoneyFormatter.parsePesoToCents("26.15"))
    assertEquals(11_169L, MoneyFormatter.parsePesoToCents("PHP 111.69"))
    assertEquals(10L, MoneyFormatter.parsePesoToCents("0.1"))
  }

  @Test
  fun parsePesoToCents_rejectsInvalidOrSubCentavoInput() {
    assertNull(MoneyFormatter.parsePesoToCents(""))
    assertNull(MoneyFormatter.parsePesoToCents("abc"))
    assertNull(MoneyFormatter.parsePesoToCents("-10.00"))
    assertNull(MoneyFormatter.parsePesoToCents("12.345"))
  }
}
