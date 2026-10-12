package dev.fajar.starter.leave.presentation.create

sealed interface LeaveCreateEffect {
    data object Back : LeaveCreateEffect

    data object VerifyAccount : LeaveCreateEffect

    data object RecheckWorkspace : LeaveCreateEffect

    data class OpenRequest(val id: String) : LeaveCreateEffect
}
