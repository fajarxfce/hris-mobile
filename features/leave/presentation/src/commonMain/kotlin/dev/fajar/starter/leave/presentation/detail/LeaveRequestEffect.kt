package dev.fajar.starter.leave.presentation.detail

sealed interface LeaveRequestEffect {
    data object Back : LeaveRequestEffect

    data object VerifyAccount : LeaveRequestEffect

    data object RecheckWorkspace : LeaveRequestEffect
}
