package dev.fajar.starter.workforce.presentation.attendance

sealed interface AttendanceEffect {
    data object Back : AttendanceEffect

    data object VerifyAccount : AttendanceEffect

    data object RecheckWorkspace : AttendanceEffect
}
