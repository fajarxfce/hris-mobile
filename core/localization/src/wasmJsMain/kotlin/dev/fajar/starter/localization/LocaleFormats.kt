@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.localization
actual fun formatNumber(value: Long, languageTag: String): String =
    intlNumber(value.toString(), languageTag)

actual fun formatDate(epochMillis: Long, languageTag: String): String =
    intlDate(epochMillis.toDouble(), languageTag)

@JsFun("(value, locale) => new Intl.NumberFormat(locale).format(BigInt(value))")
private external fun intlNumber(value: String, locale: String): String

@JsFun(
    "(value, locale) => new Intl.DateTimeFormat(locale, {dateStyle:'medium', timeStyle:'short'}).format(new Date(value))"
)
private external fun intlDate(value: Double, locale: String): String
