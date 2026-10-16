@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.*

/** Storage bounds deliberately permit unfinished breaks, reasons and interval edits. */
fun validateOvertimeActionWorkingCopy(copy: OvertimeActionWorkingCopy): AppResult<Unit> {
    val input = copy.input
    if (
        copy.action !in setOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW) ||
            Uuid.parseOrNull(copy.requestId) == null ||
            Uuid.parseOrNull(copy.employeeId) == null ||
            Uuid.parseOrNull(copy.revision) == null ||
            input.requestVersion !in 0..999 ||
            input.reason.length > 1_001 ||
            (copy.action == OvertimeAction.SUBMIT_ACTUAL) != (input.actual != null)
    )
        return overtimeFailure("client.overtime_working_copy_invalid")
    val actual = input.actual
    if (
        actual != null &&
            (!Regex("[0-9]{0,3}").matches(actual.breakMinutes) ||
                listOf(actual.startsAt, actual.endsAt).any {
                    it.year !in 2000..2100 || it.second != 0 || it.nanosecond != 0
                })
    )
        return overtimeFailure("client.overtime_working_copy_invalid")
    return AppResult.Success(Unit)
}

fun validateOvertimeActionWorkingTarget(
    selection: OvertimeRequestSelection,
    action: OvertimeAction,
): AppResult<Unit> {
    val valid = validateOvertimeRequestSelection(selection)
    if (valid is AppResult.Failed) return valid
    return if (action in setOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW))
        AppResult.Success(Unit)
    else overtimeFailure("client.overtime_working_copy_invalid")
}

fun initialOvertimeActionWorkingInput(
    action: OvertimeAction,
    request: OvertimeRequest,
): OvertimeActionWorkingInput {
    val zone = TimeZone.of(request.timezone)
    return OvertimeActionWorkingInput(
        request.version,
        if (action == OvertimeAction.SUBMIT_ACTUAL)
            OvertimeActualWorkingInput(
                request.requested.startsAt.toLocalDateTime(zone),
                request.requested.endsAt.toLocalDateTime(zone),
                request.requested.breakMinutes.toString(),
            )
        else null,
    )
}

/** Previously acquired own-request facts establish local ownership without a review deadline. */
fun validateOvertimeActionWorkingOwner(
    access: WorkforceAccess,
    selection: OvertimeRequestSelection,
    snapshot: OvertimeSnapshot<OvertimeRequestView>?,
): AppResult<OvertimeRequest> {
    val stored = validateStoredOvertime(access, snapshot)
    if (stored is AppResult.Failed) return stored
    if (snapshot == null)
        return overtimeFailure("client.overtime_working_copy_unavailable", FailureKind.Unavailable)
    val request = snapshot.value.details.request
    return if (
        request.id == selection.requestId &&
            request.employeeId == selection.employeeId &&
            snapshot.value.selection.requestId == selection.requestId &&
            snapshot.value.selection.employeeId == selection.employeeId
    )
        AppResult.Success(request)
    else overtimeFailure("employee_not_found", FailureKind.AccessDenied)
}
