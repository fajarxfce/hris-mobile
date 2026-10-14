package dev.fajar.starter.workforce.domain.replica.policies

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlinx.datetime.*

/** A local selection does not renew the acquisition timestamp or action authority. */
fun attendanceReplicaSnapshot(
    access: WorkforceAccess,
    checkpoint: WorkforceCheckpoint,
    directory: WorkforceDirectory,
    employeeId: String?,
    period: CalendarPeriod,
    after: String?,
    facts: WorkforceReplicaFacts?,
): AppResult<AttendanceSnapshot> {
    val calendar =
        if (employeeId == null) AppResult.Success(null)
        else resolveLocalWorkCalendar(period, requireNotNull(facts))
    val byDate = facts?.attendance?.associate { it.day.date to it.day }.orEmpty()
    val days =
        if (employeeId == null) emptyList()
        else
            List(period.from.daysUntil(period.until) + 1) { offset ->
                val date = period.from.plus(offset, DateTimeUnit.DAY)
                // A date without a captured/corrected fact remains unrecorded, never an absence.
                byDate[date] ?: AttendanceDay(date, emptyList(), 0, 0, false, null)
            }
    return when (calendar) {
        is AppResult.Failed -> calendar
        is AppResult.Success ->
            AppResult.Success(
                AttendanceSnapshot(
                    access,
                    checkpoint.generation,
                    pageWorkforceEmployments(directory, after),
                    employeeId,
                    period,
                    calendar.value,
                    days,
                    requireNotNull(checkpoint.refreshedAt),
                    checkpoint.refreshAfter,
                    after,
                )
            )
    }
}

fun calendarReplicaSnapshot(
    access: WorkforceAccess,
    checkpoint: WorkforceCheckpoint,
    directory: WorkforceDirectory,
    selection: WorkCalendarSelection,
    period: CalendarPeriod,
    facts: WorkforceReplicaFacts?,
): AppResult<WorkCalendarSnapshot> {
    val calendar =
        if (selection.employeeId == null) AppResult.Success(null)
        else resolveLocalWorkCalendar(period, requireNotNull(facts))
    return when (calendar) {
        is AppResult.Failed -> calendar
        is AppResult.Success ->
            AppResult.Success(
                WorkCalendarSnapshot(
                    access,
                    checkpoint.generation,
                    pageWorkforceEmployments(directory, selection.after),
                    selection,
                    period,
                    calendar.value,
                    requireNotNull(checkpoint.refreshedAt),
                    checkpoint.refreshAfter,
                )
            )
    }
}
