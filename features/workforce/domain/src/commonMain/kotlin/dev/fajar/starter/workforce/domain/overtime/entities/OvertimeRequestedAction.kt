package dev.fajar.starter.workforce.domain.overtime.entities

sealed interface OvertimeRequestedAction {
    data class Actual(val interval: OvertimeIntervalInput) : OvertimeRequestedAction

    data object Withdraw : OvertimeRequestedAction
}
