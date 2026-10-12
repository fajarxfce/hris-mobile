package dev.fajar.starter.workforce.presentation.overtime.detail

sealed interface OvertimeRequestEffect {
    data object Back : OvertimeRequestEffect

    data object VerifyAccount : OvertimeRequestEffect

    data object RecheckWorkspace : OvertimeRequestEffect
}
