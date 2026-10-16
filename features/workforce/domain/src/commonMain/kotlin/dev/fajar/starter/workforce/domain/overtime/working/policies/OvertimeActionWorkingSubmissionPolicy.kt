package dev.fajar.starter.workforce.domain.overtime.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailure
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

fun validateOvertimeActionWorkingSubmission(
    copy: OvertimeActionWorkingCopy?,
    revision: String,
    request: OvertimeRequest,
    payload: OvertimeCommandPayload,
    reason: String,
): AppResult<Unit> {
    if (
        copy == null ||
            copy.revision != revision ||
            validateOvertimeActionWorkingCopy(copy) is AppResult.Failed ||
            copy.requestId != request.id ||
            copy.employeeId != request.employeeId ||
            copy.input.requestVersion != request.version ||
            copy.input.reason.trim() != reason.trim()
    )
        return overtimeFailure("client.overtime_working_copy_changed", FailureKind.Conflict)
    val matches =
        when (payload) {
            is OvertimeCommandPayload.Plan -> false
            is OvertimeCommandPayload.Withdrawal ->
                copy.action == OvertimeAction.WITHDRAW &&
                    payload.expectedVersion == copy.input.requestVersion
            is OvertimeCommandPayload.Actual -> {
                val actual = copy.input.actual
                val zone = TimeZone.of(request.timezone)
                copy.action == OvertimeAction.SUBMIT_ACTUAL &&
                    actual != null &&
                    payload.expectedVersion == copy.input.requestVersion &&
                    actual.startsAt == payload.actual.startsAt.toLocalDateTime(zone) &&
                    actual.endsAt == payload.actual.endsAt.toLocalDateTime(zone) &&
                    actual.breakMinutes.toIntOrNull() == payload.actual.breakMinutes
            }
        }
    return if (matches) AppResult.Success(Unit)
    else overtimeFailure("client.overtime_working_copy_changed", FailureKind.Conflict)
}
