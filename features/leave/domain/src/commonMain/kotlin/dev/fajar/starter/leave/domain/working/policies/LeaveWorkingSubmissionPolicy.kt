package dev.fajar.starter.leave.domain.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.LeaveDraftSelection
import dev.fajar.starter.leave.domain.policies.leaveFailure
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import kotlinx.datetime.daysUntil

/** A reviewed submission must refer to the exact locally acknowledged input. */
fun validateLeaveWorkingSubmission(
    copy: LeaveWorkingCopy?,
    revision: String,
    selection: LeaveDraftSelection,
    reason: String,
    attachments: List<String>,
): AppResult<Unit> {
    if (
        copy == null ||
            copy.revision != revision ||
            validateLeaveWorkingCopy(copy) is AppResult.Failed
    )
        return leaveFailure("client.leave_working_copy_changed", FailureKind.Conflict)
    val input = copy.input
    if (
        copy.employeeId != selection.employeeId ||
            input.type?.id != selection.typeId ||
            input.reason.trim() != reason.trim() ||
            input.attachments.map { it.revisionId }.sorted() != attachments ||
            selection.days.size != input.from.daysUntil(input.until) + 1 ||
            selection.days.withIndex().any { (index, day) ->
                input.from.daysUntil(day.date) != index || day.portion != input.portion
            }
    )
        return leaveFailure("client.leave_working_copy_changed", FailureKind.Conflict)
    return AppResult.Success(Unit)
}
