package dev.fajar.starter.workforce.presentation.overtime.detail

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection
import dev.fajar.starter.workforce.presentation.overtime.action.OvertimeActionKind

sealed interface OvertimeRequestEffect {
    data object Back : OvertimeRequestEffect

    data object VerifyAccount : OvertimeRequestEffect

    data object RecheckWorkspace : OvertimeRequestEffect

    data class OpenAction(val selection: OvertimeRequestSelection, val kind: OvertimeActionKind) :
        OvertimeRequestEffect
}
