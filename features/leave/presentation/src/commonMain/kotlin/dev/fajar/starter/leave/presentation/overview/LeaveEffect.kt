package dev.fajar.starter.leave.presentation.overview

sealed interface LeaveEffect {
    data object Back : LeaveEffect

    data object VerifyAccount : LeaveEffect

    data object RecheckWorkspace : LeaveEffect
}
