package dev.fajar.starter.leave.presentation.create

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.leave.domain.entities.LeaveSubmissionRecord
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache

internal fun LeaveCreateState.withCreateFailure(value: Failure): LeaveCreateState {
    if (!leaveFailureInvalidatesCache(value)) return copy(failure = value)
    return copy(
        restriction = value,
        failure = null,
        catalog = catalog.copy(items = emptyList()),
        form = form?.copy(reason = "", type = null, attachments = emptyList()),
        persistence = null,
        preview = null,
        command = null,
        commandReady = false,
        dialog = null,
        evidenceTarget = null,
    )
}

internal fun LeaveCreateState.withRetainedSubmission(
    record: LeaveSubmissionRecord?
): LeaveCreateState {
    val previous = command
    if (
        record != null &&
            previous?.command?.id == record.command.id &&
            record.revision < previous.revision
    )
        return this
    return copy(
        command = record,
        commandReady = true,
        preview = preview.takeIf { record == null },
        dialog = dialog.takeIf { record == null },
        evidenceTarget = evidenceTarget.takeIf { record == null },
    )
}
