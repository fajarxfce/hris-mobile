@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import kotlin.uuid.Uuid
import kotlinx.datetime.daysUntil

/** Bounds local input without requiring a current catalog or a complete submission. */
fun validateLeaveWorkingCopy(copy: LeaveWorkingCopy): AppResult<Unit> {
    val input = copy.input
    if (
        Uuid.parseOrNull(copy.employeeId) == null ||
            Uuid.parseOrNull(copy.revision) == null ||
            input.from.year !in 1900..2200 ||
            input.until.year !in 1900..2200 ||
            input.from.daysUntil(input.until) !in 0..365 ||
            input.reason.length > 1_001 ||
            input.attachments.size > MAX_LEAVE_ATTACHMENTS ||
            input.attachments.distinctBy { it.revisionId }.size != input.attachments.size
    )
        return leaveFailure("client.leave_working_copy_invalid")
    val type = input.type
    if (
        type != null &&
            (Uuid.parseOrNull(type.id) == null ||
                !Regex("[A-Z][A-Z0-9_-]{0,31}").matches(type.code) ||
                type.name.isBlank() ||
                type.name.length > 200 ||
                type.effectiveFrom.year !in 1900..2200 ||
                type.version !in 0..9_007_199_254_740_991L ||
                type.appliedRevision !in 0..type.version)
    )
        return leaveFailure("client.leave_working_copy_invalid")
    if (
        input.attachments.any {
            Uuid.parseOrNull(it.documentId) == null ||
                Uuid.parseOrNull(it.revisionId) == null ||
                it.fileName.isBlank() ||
                it.fileName.length > 255 ||
                (it.title != null && (it.title.isBlank() || it.title.length > 200)) ||
                it.mediaType.isBlank() ||
                it.mediaType.length > 200 ||
                it.size !in 1..104_857_600 ||
                !Regex("[0-9a-f]{64}").matches(it.sha256)
        }
    )
        return leaveFailure("client.leave_working_copy_invalid")
    return AppResult.Success(Unit)
}

fun validateLeaveWorkingOwner(
    access: LeaveAccess,
    employeeId: String,
    local: LeaveReplicaDirectory,
): AppResult<Unit> {
    val stored = validateStoredLeaveReplica(access, local.checkpoint)
    if (stored is AppResult.Failed) return stored
    val directory =
        local.directory
            ?: return leaveFailure("client.leave_working_copy_unavailable", FailureKind.Unavailable)
    return if (
        directory.accountId == access.accountId &&
            directory.companyId == access.companyId &&
            directory.items.any { it.id == employeeId }
    )
        AppResult.Success(Unit)
    else leaveFailure("employee_not_found", FailureKind.AccessDenied)
}

/** Local ownership survives token renewal; replacement sessions and permissions do not. */
fun validateLeaveWorkingContinuation(
    expected: LeaveAccess,
    current: AppResult<LeaveAccess>,
): AppResult<LeaveAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (sameLeaveSessionOwner(expected, current.value)) current
            else leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
    }
