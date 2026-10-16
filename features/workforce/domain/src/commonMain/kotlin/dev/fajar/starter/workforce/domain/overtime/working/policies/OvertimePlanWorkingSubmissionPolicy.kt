package dev.fajar.starter.workforce.domain.overtime.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeIntervalInput
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimePreparation
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A planning command references the exact acknowledged local input, not a later edit. */
fun validateOvertimePlanWorkingSubmission(
    copy: OvertimePlanWorkingCopy?,
    revision: String,
    prepared: OvertimePreparation,
    interval: OvertimeIntervalInput,
    reason: String,
): AppResult<Unit> {
    if (
        copy == null ||
            copy.revision != revision ||
            validateOvertimePlanWorkingCopy(copy) is AppResult.Failed
    )
        return overtimeFailure("client.overtime_working_copy_changed", FailureKind.Conflict)
    val zone = TimeZone.of(prepared.review.timezone)
    val start = interval.startsAt.toLocalDateTime(zone)
    val end = interval.endsAt.toLocalDateTime(zone)
    val input = copy.input
    return if (
        copy.employeeId == prepared.selection.employeeId &&
            input.workDate == prepared.selection.workDate &&
            input.startDate == start.date &&
            input.startsAt == start.time &&
            input.endDate == end.date &&
            input.endsAt == end.time &&
            input.breakMinutes.toIntOrNull() == interval.breakMinutes &&
            input.reason.trim() == reason.trim()
    )
        AppResult.Success(Unit)
    else overtimeFailure("client.overtime_working_copy_changed", FailureKind.Conflict)
}
