package dev.fajar.starter.localization

import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

actual fun formatNumber(value: Long, languageTag: String): String =
    NumberFormat.getIntegerInstance(Locale.forLanguageTag(languageTag)).format(value)

actual fun formatLocalDate(isoDate: String, languageTag: String): String {
    require(isoDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
    val utc = TimeZone.getTimeZone("UTC")
    val date =
        requireNotNull(
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .apply {
                    timeZone = utc
                    isLenient = false
                }
                .parse(isoDate)
        )
    return SimpleDateFormat("EEE, d MMM yyyy", Locale.forLanguageTag(languageTag))
        .apply { timeZone = utc }
        .format(date)
}

actual fun formatDate(epochMillis: Long, languageTag: String): String =
    DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.forLanguageTag(languageTag),
        )
        .format(Date(epochMillis))
