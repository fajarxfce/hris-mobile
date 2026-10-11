package dev.fajar.starter.workforce.presentation.attendance.history

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.WorkforceEmployment
import kotlinx.datetime.LocalDate

data class AttendanceHistoryState(
    val selection: AttendanceSelection = AttendanceSelection(window = AttendanceWindow.MONTHLY),
    val content: AttendanceHistoryContent? = null,
    val month: AttendanceMonthFilter? = null,
    val picker: AttendanceMonthPicker? = null,
    val selectedDay: AttendanceHistoryDay? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val needsVerification: Boolean = false,
)

data class AttendanceHistoryContent(
    val snapshot: AttendanceSnapshot,
    val employee: WorkforceEmployment?,
    val summary: AttendanceSummary,
)

data class AttendanceMonthFilter(
    val selected: LocalDate,
    val today: LocalDate,
    val previous: LocalDate?,
    val next: LocalDate?,
)

data class AttendanceMonthPicker(
    val year: Int,
    val months: List<AttendanceMonthOption>,
    val previousYear: Int?,
    val nextYear: Int?,
)

data class AttendanceMonthOption(val date: LocalDate, val enabled: Boolean)
