@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.mappers

import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun WorkCalendarDto.toCalendar(period: CalendarPeriod): WorkCalendar {
    require(
        period.from.daysUntil(period.until) in 0..30 &&
            days.size == period.from.daysUntil(period.until) + 1
    )
    require(scheduleVersion == null || scheduleVersion in 0..9_007_199_254_740_991L)
    val mapped =
        days.mapIndexed { index, day ->
            if (day.origin == CalendarOrigin.PATTERN.name) {
                val revision = day.originVersion
                require(scheduleVersion != null && revision != null && revision <= scheduleVersion)
            }
            day.toWorkDay(period.from.plus(index, DateTimeUnit.DAY))
        }
    return WorkCalendar(scheduleVersion, mapped.toList())
}

fun CalendarShiftDto.toShift(): CalendarShift {
    Uuid.parse(id)
    require(revision in 0..9_007_199_254_740_991L)
    val value = details
    require(
        value.code.isNotBlank() &&
            value.code.length <= 32 &&
            value.name.isNotBlank() &&
            value.name.length <= 200
    )
    require(value.timezone.length in 1..100 && value.breakMinutes in 0..1440)
    TimeZone.of(value.timezone)
    val start = LocalTime.parse(value.startsAt)
    val end = LocalTime.parse(value.endsAt)
    require(start.second == 0 && start.nanosecond == 0 && end.second == 0 && end.nanosecond == 0)
    return CalendarShift(
        id,
        revision,
        value.code,
        value.name,
        start,
        end,
        value.breakMinutes,
        value.timezone,
        CalendarWorkMode.valueOf(value.mode),
    )
}

fun WorkCalendar.toDto() = WorkCalendarDto(scheduleVersion, days.map { it.toDto() })

fun CalendarShift.toDto() =
    CalendarShiftDto(
        id,
        revision,
        CalendarShiftDetailsDto(
            code,
            name,
            startsAt.toString(),
            endsAt.toString(),
            breakMinutes,
            timezone,
            mode.name,
        ),
    )
