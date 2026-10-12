package dev.fajar.starter.leave.presentation.overview

sealed interface LeaveEffect {
    data object Back : LeaveEffect

    data object VerifyAccount : LeaveEffect

    data object RecheckWorkspace : LeaveEffect

    data class OpenRequest(val id: String) : LeaveEffect

    data class CreateRequest(val employeeId: String, val asOf: String) : LeaveEffect
}
