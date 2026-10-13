package dev.fajar.starter.common.decimal

import kotlin.test.*

class DecimalAmountTest {
    @Test
    fun valuesRetainDigitsBeyondFloatingPointPrecision() {
        val amount = DecimalAmount.parse("9007199254740993.010000000000000001")
        assertEquals("9007199254740993.010000000000000001", amount.value)
        assertFalse(amount.isZero)
        assertFalse(amount.isNegative)
        assertNotEquals(amount, DecimalAmount.parse("9007199254740993.010000000000000002"))
    }

    @Test
    fun equivalentScalesAndSignedZeroHaveValueEquality() {
        assertEquals(DecimalAmount.parse("125.5000"), DecimalAmount.parse("125.5"))
        assertEquals(DecimalAmount.parse("-0.000"), DecimalAmount.parse("0"))
        assertTrue(DecimalAmount.parse("-0.000").isZero)
        assertFalse(DecimalAmount.parse("-0").isNegative)
        assertTrue(DecimalAmount.parse("-125.50").isNegative)
    }

    @Test
    fun malformedUnboundedAndNonDecimalInputsAreRejected() {
        for (value in
            listOf(
                "",
                " 1",
                "1 ",
                "+1",
                "01",
                "1.",
                ".5",
                "1e3",
                "NaN",
                "1,000",
                "١",
                "1".repeat(37),
                "0." + "1".repeat(19),
            )) {
            assertFailsWith<IllegalArgumentException> { DecimalAmount.parse(value) }
        }
        assertEquals("1".repeat(36), DecimalAmount.parse("1".repeat(36)).value)
    }
}
