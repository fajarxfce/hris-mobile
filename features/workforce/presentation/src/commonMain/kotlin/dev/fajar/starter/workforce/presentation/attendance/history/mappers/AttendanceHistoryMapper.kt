package dev.fajar.starter.workforce.presentation.attendance.history.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.presentation.attendance.history.*
import kotlin.time.Instant
import kotlinx.datetime.*

fun AttendanceView.toHistoryContent(now: Instant): AttendanceHistoryContent? {
    val snapshot = snapshot ?: return null
    return AttendanceHistoryContent(
        snapshot,
        snapshot.directory.items.singleOrNull { it.id == snapshot.employeeId },
        summarizeAttendanceHistory(snapshot, commands, now),
    )
}

fun attendanceMonthFilter(selected: LocalDate, today: LocalDate): AttendanceMonthFilter =
    AttendanceMonthFilter(
        selected,
        today,
        selected.minus(1, DateTimeUnit.MONTH).takeIf { isAvailableMonth(it, today) },
        selected.plus(1, DateTimeUnit.MONTH).takeIf { isAvailableMonth(it, today) },
    )

fun attendanceMonthPicker(year: Int, today: LocalDate): AttendanceMonthPicker =
    AttendanceMonthPicker(
        year,
        (1..12).map { month ->
            val date = LocalDate(year, month, 1)
            AttendanceMonthOption(date, isAvailableMonth(date, today))
        },
        (year - 1).takeIf { isAvailableMonth(LocalDate(it, 1, 1), today) },
        (year + 1).takeIf { isAvailableMonth(LocalDate(it, 1, 1), today) },
    )

private fun isAvailableMonth(date: LocalDate, today: LocalDate): Boolean =
    attendancePeriod(today, AttendanceSelection(window = AttendanceWindow.MONTHLY, from = date)) is
        AppResult.Success

fun AttendanceSelection.matches(snapshot: AttendanceSnapshot): Boolean =
    (employeeId == null || employeeId == snapshot.employeeId) &&
        (from == null || from == snapshot.period.from) &&
        after == snapshot.after
