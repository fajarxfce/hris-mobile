package dev.fajar.starter.workforce.presentation.calendar.formatters

import androidx.compose.runtime.Composable
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun calendarTimeRange(day: WorkDay.Work): String =
    appString(
        if (day.endsAt.toLocalDateTime(TimeZone.of(day.shift.timezone)).date > day.date)
            AppString.CalendarOvernightHours
        else AppString.CalendarHours,
        day.shift.startsAt.toString(),
        day.shift.endsAt.toString(),
    )

@Composable
fun calendarDuration(minutes: Long): String =
    appString(
        AppString.CalendarDuration,
        formatNumber(minutes / 60, appLanguageTag()),
        formatNumber(minutes % 60, appLanguageTag()),
    )

@Composable
fun calendarWorkMode(mode: CalendarWorkMode): String =
    appString(
        when (mode) {
            CalendarWorkMode.ONSITE -> AppString.CalendarOnsite
            CalendarWorkMode.REMOTE -> AppString.CalendarRemote
            CalendarWorkMode.FIELD -> AppString.CalendarField
        }
    )

@Composable
fun calendarOrigin(origin: CalendarOrigin): String =
    appString(
        when (origin) {
            CalendarOrigin.PATTERN -> AppString.CalendarPattern
            CalendarOrigin.ROSTER -> AppString.CalendarRoster
            CalendarOrigin.HOLIDAY -> AppString.CalendarHoliday
        }
    )
