package dev.fajar.starter.localization

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlin.test.*

class DecimalFormattingTest {
    @Test
    fun englishAndIndonesianRetainExactLargeAmountsAndRefunds() {
        val value = DecimalAmount.parse("-9007199254740993.01")
        assertEquals("-9,007,199,254,740,993.01", formatDecimal(value, "en-US"))
        assertEquals("-9.007.199.254.740.993,01", formatDecimal(value, "id-ID"))
    }

    @Test
    fun displayScalePadsWithoutRoundingAwaySignificantDigits() {
        assertEquals("0,00", formatDecimal(DecimalAmount.parse("-0.00"), "id", 2))
        assertEquals("1,250.125", formatDecimal(DecimalAmount.parse("1250.125"), "en", 2))
        assertEquals("1.250", formatDecimal(DecimalAmount.parse("1250.00"), "id"))
        assertFailsWith<IllegalArgumentException> {
            formatDecimal(DecimalAmount.parse("1"), "en", 19)
        }
    }
}
