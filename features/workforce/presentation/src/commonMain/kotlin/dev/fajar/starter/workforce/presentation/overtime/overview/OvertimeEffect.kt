package dev.fajar.starter.workforce.presentation.overtime.overview

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimePlanningSelection
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeRequestSelection

sealed interface OvertimeEffect {
    data object Back : OvertimeEffect

    data object VerifyAccount : OvertimeEffect

    data object RecheckWorkspace : OvertimeEffect

    data class OpenRequest(val selection: OvertimeRequestSelection) : OvertimeEffect

    data class PlanRequest(val selection: OvertimePlanningSelection) : OvertimeEffect
}
