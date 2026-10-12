package dev.fajar.starter.workforce.presentation.overtime.detail

import dev.fajar.starter.workforce.presentation.overtime.action.OvertimeActionKind

sealed interface OvertimeRequestEvent {
    data object Entered : OvertimeRequestEvent

    data object Stopped : OvertimeRequestEvent

    data object RefreshRequested : OvertimeRequestEvent

    data object BackRequested : OvertimeRequestEvent

    data object VerificationRequested : OvertimeRequestEvent

    data class ActionRequested(val kind: OvertimeActionKind) : OvertimeRequestEvent

    data class HistoryPageRequested(val after: Long?) : OvertimeRequestEvent
}
