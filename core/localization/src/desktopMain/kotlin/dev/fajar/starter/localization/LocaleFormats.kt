package dev.fajar.starter.localization

import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

actual fun formatNumber(value: Long, languageTag: String): String =
    NumberFormat.getIntegerInstance(Locale.forLanguageTag(languageTag)).format(value)

actual fun formatDate(epochMillis: Long, languageTag: String): String =
    DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.forLanguageTag(languageTag),
        )
        .format(Date(epochMillis))
