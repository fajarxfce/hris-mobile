@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.mappers

import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun WorkCalendarDto.toCalendar(period: CalendarPeriod): WorkCalendar {
    require(period.from.daysUntil(period.until) == 6 && days.size == 7)
    require(scheduleVersion == null || scheduleVersion in 0..9_007_199_254_740_991L)
    val mapped =
        days.mapIndexed { index, day ->
            val date = LocalDate.parse(day.workDate)
            require(date == period.from.plus(index, DateTimeUnit.DAY))
            val origin = day.origin?.let(CalendarOrigin::valueOf)
            val revision = day.originVersion
            require(revision == null || revision in 0..9_007_199_254_740_991L)
            if (origin == CalendarOrigin.PATTERN)
                require(scheduleVersion != null && revision != null && revision <= scheduleVersion)
            day.holidayId?.let(Uuid::parse)
            when (day.kind) {
                "WORK" -> {
                    require(origin == CalendarOrigin.PATTERN || origin == CalendarOrigin.ROSTER)
                    require(day.holidayId == null && revision != null)
                    val shift = requireNotNull(day.shift).toShift()
                    val start = Instant.parse(requireNotNull(day.startsAt))
                    val end = Instant.parse(requireNotNull(day.endsAt))
                    val zone = TimeZone.of(shift.timezone)
                    val localStart = start.toLocalDateTime(zone)
                    val localEnd = end.toLocalDateTime(zone)
                    require(localStart.date == date && localStart.time == shift.startsAt)
                    require(localEnd.time == shift.endsAt && date.daysUntil(localEnd.date) in 0..1)
                    require(end > start && (end - start).inWholeMinutes <= 2880)
                    require(
                        day.plannedMinutes >= 0 &&
                            day.plannedMinutes == (end - start).inWholeMinutes - shift.breakMinutes
                    )
                    WorkDay.Work(date, shift, start, end, day.plannedMinutes, origin, revision)
                }
                "OFF" -> {
                    require(
                        day.shift == null &&
                            day.startsAt == null &&
                            day.endsAt == null &&
                            day.plannedMinutes == 0L
                    )
                    require(origin != null && revision != null)
                    require((origin == CalendarOrigin.HOLIDAY) == (day.holidayId != null))
                    WorkDay.Off(date, origin, revision, day.holidayId)
                }
                "UNASSIGNED" -> {
                    require(
                        day.shift == null &&
                            day.startsAt == null &&
                            day.endsAt == null &&
                            day.plannedMinutes == 0L
                    )
                    require(origin == null && revision == null && day.holidayId == null)
                    WorkDay.Unassigned(date)
                }
                else -> error("Unknown calendar day kind.")
            }
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

fun WorkCalendar.toDto() =
    WorkCalendarDto(
        scheduleVersion,
        days.map { day ->
            when (day) {
                is WorkDay.Work ->
                    WorkDayDto(
                        day.date.toString(),
                        "WORK",
                        day.shift.toDto(),
                        day.startsAt.toString(),
                        day.endsAt.toString(),
                        day.plannedMinutes,
                        day.origin.name,
                        day.originVersion,
                    )
                is WorkDay.Off ->
                    WorkDayDto(
                        day.date.toString(),
                        "OFF",
                        origin = day.origin.name,
                        originVersion = day.originVersion,
                        holidayId = day.holidayId,
                    )
                is WorkDay.Unassigned -> WorkDayDto(day.date.toString(), "UNASSIGNED")
            }
        },
    )

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
