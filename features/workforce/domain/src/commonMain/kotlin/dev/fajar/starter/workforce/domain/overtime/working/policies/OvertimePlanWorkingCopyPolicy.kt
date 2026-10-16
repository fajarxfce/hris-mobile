@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeReplicaDirectory
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import kotlin.uuid.Uuid

/**
 * Bounds storage input while retaining incomplete times, empty breaks and unfinished date edits.
 */
fun validateOvertimePlanWorkingCopy(copy: OvertimePlanWorkingCopy): AppResult<Unit> {
    val input = copy.input
    if (
        Uuid.parseOrNull(copy.employeeId) == null ||
            Uuid.parseOrNull(copy.revision) == null ||
            input.workDate.year !in 2000..2100 ||
            input.startDate.year !in 2000..2100 ||
            input.endDate.year !in 2000..2100 ||
            !Regex("[0-9]{0,5}").matches(input.breakMinutes) ||
            input.reason.length > 1_001 ||
            listOfNotNull(input.startsAt, input.endsAt).any { it.second != 0 || it.nanosecond != 0 }
    )
        return overtimeFailure("client.overtime_working_copy_invalid")
    return AppResult.Success(Unit)
}

fun validateOvertimePlanWorkingOwner(
    access: WorkforceAccess,
    employeeId: String,
    local: OvertimeReplicaDirectory,
): AppResult<Unit> {
    val stored = validateStoredOvertimeReplica(access, local.checkpoint)
    if (stored is AppResult.Failed) return stored
    val directory =
        local.directory
            ?: return overtimeFailure(
                "client.overtime_working_copy_unavailable",
                FailureKind.Unavailable,
            )
    return if (
        directory.accountId == access.accountId &&
            directory.companyId == access.companyId &&
            directory.items.any { it.id == employeeId }
    )
        AppResult.Success(Unit)
    else overtimeFailure("employee_not_found", FailureKind.AccessDenied)
}

/**
 * Local edits survive token renewal, while replacement sessions and changed grants cancel pending
 * work.
 */
fun validateOvertimeWorkingContinuation(
    expected: WorkforceAccess,
    current: AppResult<WorkforceAccess>,
): AppResult<WorkforceAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (
                expected.sessionId == current.value.sessionId &&
                    sameStoredOvertimeScope(expected, current.value)
            )
                current
            else overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
    }
