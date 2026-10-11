package dev.fajar.starter.localization

import platform.Foundation.*

actual fun formatLocalDate(isoDate: String, languageTag: String): String {
    val utc = NSTimeZone.timeZoneForSecondsFromGMT(0)
    val parser =
        NSDateFormatter().apply {
            locale = NSLocale("en_US_POSIX")
            timeZone = utc
            dateFormat = "yyyy-MM-dd"
            lenient = false
        }
    val date = requireNotNull(parser.dateFromString(isoDate))
    return NSDateFormatter()
        .apply {
            locale = NSLocale(languageTag)
            timeZone = utc
            setLocalizedDateFormatFromTemplate("EEE d MMM y")
        }
        .stringFromDate(date)
}

actual fun formatNumber(value: Long, languageTag: String): String =
    NSNumberFormatter()
        .apply {
            locale = NSLocale(languageTag)
            numberStyle = NSNumberFormatterDecimalStyle
            maximumFractionDigits = 0u
        }
        .stringFromNumber(NSNumber(longLong = value)) ?: value.toString()

actual fun formatDate(epochMillis: Long, languageTag: String): String =
    NSDateFormatter()
        .apply {
            locale = NSLocale(languageTag)
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = NSDateFormatterShortStyle
        }
        .stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis.toDouble() / 1000))
