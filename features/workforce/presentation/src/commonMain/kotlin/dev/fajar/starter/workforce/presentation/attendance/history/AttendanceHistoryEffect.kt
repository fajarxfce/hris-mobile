package dev.fajar.starter.workforce.presentation.attendance.history

sealed interface AttendanceHistoryEffect {
    data object Back : AttendanceHistoryEffect

    data object VerifyAccount : AttendanceHistoryEffect

    data object RecheckWorkspace : AttendanceHistoryEffect
}
