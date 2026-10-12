package dev.fajar.starter.leave.presentation.create

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import kotlinx.datetime.*

data class LeaveCreateForm(
    val from: LocalDate,
    val until: LocalDate,
    val type: LeaveTypeOption? = null,
    val portion: LeavePortion = LeavePortion.FULL,
    val reason: String = "",
)

/** Expands the bounded date-range control into explicit input; the server selects working days. */
internal fun LeaveCreateForm.toSelection(employeeId: String): AppResult<LeaveDraftSelection> {
    val selected = type ?: return leaveFailure("leave_type_unavailable")
    val distance = from.daysUntil(until)
    if (distance !in 0..365) return leaveFailure("leave_range_too_large")
    return AppResult.Success(
        LeaveDraftSelection(
            employeeId,
            selected.id,
            List(distance + 1) { LeaveRequestedDay(from.plus(it, DateTimeUnit.DAY), portion) },
        )
    )
}
