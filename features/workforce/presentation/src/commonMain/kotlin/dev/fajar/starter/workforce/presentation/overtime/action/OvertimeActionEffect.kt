package dev.fajar.starter.workforce.presentation.overtime.action

sealed interface OvertimeActionEffect {
    data object Back : OvertimeActionEffect

    data object VerifyAccount : OvertimeActionEffect

    data object RecheckWorkspace : OvertimeActionEffect
}
