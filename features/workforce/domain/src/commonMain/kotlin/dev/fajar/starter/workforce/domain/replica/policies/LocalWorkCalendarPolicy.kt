package dev.fajar.starter.workforce.domain.replica.policies

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.calendarFailure
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceReplicaFacts
import kotlinx.datetime.*

/** Matches the server: roster override, active holiday, then the effective weekly rule. */
fun resolveLocalWorkCalendar(
    period: CalendarPeriod,
    facts: WorkforceReplicaFacts,
): AppResult<WorkCalendar> {
    val length = period.from.daysUntil(period.until)
    if (length !in 0..30) return calendarFailure("invalid_calendar_range")
    val roster = facts.roster.associateBy { it.workDate }
    val holidays = facts.holidays.filter { it.active }.associateBy { it.workDate }
    val days = mutableListOf<WorkDay>()
    for (offset in 0..length) {
        val date = period.from.plus(offset, DateTimeUnit.DAY)
        val override = roster[date]
        val holiday = holidays[date]
        val assignment =
            facts.schedules.filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }
        val result =
            when {
                override != null ->
                    override.shift?.let {
                        localScheduledDay(date, it, CalendarOrigin.ROSTER, override.version)
                    }
                        ?: AppResult.Success(
                            WorkDay.Off(date, CalendarOrigin.ROSTER, override.version, null)
                        )
                holiday != null ->
                    AppResult.Success(
                        WorkDay.Off(date, CalendarOrigin.HOLIDAY, holiday.version, holiday.id)
                    )
                assignment == null -> AppResult.Success(WorkDay.Unassigned(date))
                else ->
                    assignment.days[date.dayOfWeek]?.let {
                        localScheduledDay(date, it, CalendarOrigin.PATTERN, assignment.version)
                    }
                        ?: AppResult.Success(
                            WorkDay.Off(date, CalendarOrigin.PATTERN, assignment.version, null)
                        )
            }
        if (result is AppResult.Failed) return result
        days += (result as AppResult.Success).value
    }
    return AppResult.Success(WorkCalendar(null, days.toList()))
}

/** Reject gaps rather than silently shifting a local wall time; overlaps use the earlier offset. */
fun localScheduledDay(
    date: LocalDate,
    shift: CalendarShift,
    origin: CalendarOrigin,
    version: Long,
): AppResult<WorkDay.Work> {
    val zone = TimeZone.of(shift.timezone)
    val localStart = date.atTime(shift.startsAt)
    val endDate = if (shift.endsAt > shift.startsAt) date else date.plus(1, DateTimeUnit.DAY)
    val localEnd = endDate.atTime(shift.endsAt)
    val start = localStart.toInstant(zone)
    val end = localEnd.toInstant(zone)
    if (start.toLocalDateTime(zone) != localStart || end.toLocalDateTime(zone) != localEnd)
        return calendarFailure("shift_timezone_gap")
    val plannedMinutes = (end - start).inWholeMinutes - shift.breakMinutes
    if (plannedMinutes <= 0) return calendarFailure("invalid_shift_duration")
    return AppResult.Success(WorkDay.Work(date, shift, start, end, plannedMinutes, origin, version))
}
