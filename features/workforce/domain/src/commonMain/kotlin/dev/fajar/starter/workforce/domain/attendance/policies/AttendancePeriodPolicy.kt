@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.attendance.policies

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.CalendarPeriod
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun validateAttendanceSelection(selection: AttendanceSelection): AppResult<Unit> =
    when {
        selection.after != null && (selection.after.isBlank() || selection.after.length > 32) ->
            attendanceFailure("invalid_page")
        selection.employeeId != null && Uuid.parseOrNull(selection.employeeId) == null ->
            attendanceFailure("invalid_employee")
        selection.from != null &&
            (selection.window != AttendanceWindow.MONTHLY ||
                selection.from.year !in 1900..9998 ||
                selection.from.day != 1) -> attendanceFailure("invalid_attendance_range")
        else -> AppResult.Success(Unit)
    }

fun attendancePeriod(today: LocalDate, selection: AttendanceSelection): AppResult<CalendarPeriod> {
    val validation = validateAttendanceSelection(selection)
    if (validation is AppResult.Failed) return validation
    if (selection.window == AttendanceWindow.RECENT)
        return AppResult.Success(
            CalendarPeriod(today.minus(5, DateTimeUnit.DAY), today.plus(1, DateTimeUnit.DAY))
        )
    val start = selection.from ?: LocalDate(today.year, today.month, 1)
    if (start > today) return attendanceFailure("invalid_attendance_range")
    return AppResult.Success(
        CalendarPeriod(start, start.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY))
    )
}
