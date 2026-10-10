package dev.fajar.starter.localization

import platform.Foundation.*

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
