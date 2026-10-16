package dev.fajar.starter.workforce.presentation.overtime.plan

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeCommandRecord
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache

internal fun OvertimePlanState.withPlanFailure(value: Failure): OvertimePlanState {
    if (!overtimeFailureInvalidatesCache(value))
        return copy(
            failure = value,
            reviewExpired = reviewExpired || value.code == "client.overtime_review_expired",
        )
    return copy(
        restriction = value,
        failure = null,
        prepared = null,
        command = null,
        commandReady = false,
        form = form?.copy(reason = "", startsAt = null, endsAt = null),
        dialog = null,
    )
}

internal fun OvertimePlanState.withPlanCommand(record: OvertimeCommandRecord?): OvertimePlanState {
    if (restriction != null) return this
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
        prepared = prepared.takeIf { record == null },
        dialog = dialog.takeIf { record == null },
    )
}
