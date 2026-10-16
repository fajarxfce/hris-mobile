package dev.fajar.starter.workforce.presentation.overtime.action

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import kotlinx.datetime.*

typealias OvertimeActionForm =
    dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingInput

typealias OvertimeActualInput =
    dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActualWorkingInput

internal fun OvertimeActualInput.toIntervalInput(): AppResult<OvertimeLocalIntervalInput> {
    val pause = breakMinutes.toIntOrNull() ?: return overtimeFailure("invalid_overtime_interval")
    return AppResult.Success(OvertimeLocalIntervalInput(startsAt, endsAt, pause))
}

enum class OvertimeActualField {
    START,
    END,
}

sealed interface OvertimeActionDialog {
    data class Date(val field: OvertimeActualField, val month: LocalDate) : OvertimeActionDialog

    data class Time(val field: OvertimeActualField, val hours: String, val minutes: String) :
        OvertimeActionDialog {
        val value: LocalTime?
            get() {
                val hour = hours.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                val minute = minutes.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
                return LocalTime(hour, minute)
            }
    }

    data object Discard : OvertimeActionDialog

    data object ReloadDraft : OvertimeActionDialog

    data object DiscardDraft : OvertimeActionDialog

    data object LeaveUnsaved : OvertimeActionDialog
}
