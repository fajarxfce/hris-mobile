package dev.fajar.starter.workforce.presentation.overtime.plan

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection

sealed interface OvertimePlanEffect {
    data object Back : OvertimePlanEffect

    data object VerifyAccount : OvertimePlanEffect

    data object RecheckWorkspace : OvertimePlanEffect

    data class OpenRequest(val selection: OvertimeRequestSelection) : OvertimePlanEffect
}
