package dev.fajar.starter.workforce.presentation.overtime.plan

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeLocalIntervalInput
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import kotlinx.datetime.*

data class OvertimePlanForm(
    val workDate: LocalDate,
    val startDate: LocalDate = workDate,
    val endDate: LocalDate = workDate,
    val startsAt: LocalTime? = null,
    val endsAt: LocalTime? = null,
    val breakMinutes: String = "0",
    val reason: String = "",
)

internal fun OvertimePlanForm.toIntervalInput(): AppResult<OvertimeLocalIntervalInput> {
    val start = startsAt ?: return overtimeFailure("client.overtime_time_required")
    val end = endsAt ?: return overtimeFailure("client.overtime_time_required")
    val pause = breakMinutes.toIntOrNull() ?: return overtimeFailure("invalid_overtime_interval")
    return AppResult.Success(
        OvertimeLocalIntervalInput(
            LocalDateTime(startDate, start),
            LocalDateTime(endDate, end),
            pause,
        )
    )
}

enum class OvertimePlanDateField {
    WORK,
    START,
    END,
}

enum class OvertimeTimeField {
    START,
    END,
}

sealed interface OvertimePlanDialog {
    data class Date(val field: OvertimePlanDateField, val month: LocalDate) : OvertimePlanDialog

    data class Time(val field: OvertimeTimeField, val hours: String, val minutes: String) :
        OvertimePlanDialog {
        val value: LocalTime?
            get() {
                val hour = hours.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
                val minute = minutes.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
                return LocalTime(hour, minute)
            }
    }

    data object Discard : OvertimePlanDialog
}
