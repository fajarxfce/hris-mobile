package dev.fajar.starter.localization

import dev.fajar.starter.common.decimal.DecimalAmount

/** Formats retained digits without rounding or converting through a platform number type. */
fun formatDecimal(
    amount: DecimalAmount,
    languageTag: String,
    minimumFractionDigits: Int = 0,
): String {
    require(minimumFractionDigits in 0..18)
    val indonesian = languageTag.substringBefore('-').substringBefore('_').equals("id", true)
    val grouping = if (indonesian) "." else ","
    val separator = if (indonesian) "," else "."
    val unsigned = amount.value.removePrefix("-")
    val integer =
        unsigned.substringBefore('.').reversed().chunked(3).joinToString(grouping).reversed()
    val fraction = unsigned.substringAfter('.', "").padEnd(minimumFractionDigits, '0')
    return (if (amount.isNegative) "-" else "") +
        integer +
        if (fraction.isEmpty()) "" else separator + fraction
}
