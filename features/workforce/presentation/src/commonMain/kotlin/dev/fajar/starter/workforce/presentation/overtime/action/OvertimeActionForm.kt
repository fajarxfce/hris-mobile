package dev.fajar.starter.workforce.presentation.overtime.action

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import kotlinx.datetime.*

data class OvertimeActionForm(
    val requestVersion: Long,
    val actual: OvertimeActualInput?,
    val reason: String = "",
)

data class OvertimeActualInput(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val breakMinutes: String,
)

internal fun overtimeActionForm(
    kind: OvertimeActionKind,
    request: OvertimeRequest,
): OvertimeActionForm {
    val zone = TimeZone.of(request.timezone)
    return OvertimeActionForm(
        request.version,
        if (kind == OvertimeActionKind.ACTUAL)
            OvertimeActualInput(
                request.requested.startsAt.toLocalDateTime(zone),
                request.requested.endsAt.toLocalDateTime(zone),
                request.requested.breakMinutes.toString(),
            )
        else null,
    )
}

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
}
