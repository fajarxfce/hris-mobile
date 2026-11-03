package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.localization.AppString
import kotlin.time.Instant
import kotlinx.datetime.*

internal fun chatMessageTime(timestamp: Long, zone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

internal fun chatMessageDate(timestamp: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone).date

internal fun chatDayResource(date: LocalDate, today: LocalDate): AppString? =
    when (date.daysUntil(today)) {
        0 -> AppString.CalendarToday
        1 -> AppString.ChatYesterday
        in 2..6 ->
            when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> AppString.ChatMonday
                DayOfWeek.TUESDAY -> AppString.ChatTuesday
                DayOfWeek.WEDNESDAY -> AppString.ChatWednesday
                DayOfWeek.THURSDAY -> AppString.ChatThursday
                DayOfWeek.FRIDAY -> AppString.ChatFriday
                DayOfWeek.SATURDAY -> AppString.ChatSaturday
                DayOfWeek.SUNDAY -> AppString.ChatSunday
            }
        else -> null
    }

internal fun chatCalendarDate(date: LocalDate): String =
    "${date.day.toString().padStart(2, '0')}/${date.month.number.toString().padStart(2, '0')}/${date.year}"
