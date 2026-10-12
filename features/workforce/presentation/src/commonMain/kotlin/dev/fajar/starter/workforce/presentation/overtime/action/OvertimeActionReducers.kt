package dev.fajar.starter.workforce.presentation.overtime.action

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache

internal fun OvertimeActionState.withActionFailure(value: Failure): OvertimeActionState =
    if (!overtimeFailureInvalidatesCache(value)) copy(failure = value)
    else
        copy(
            restriction = value,
            failure = null,
            content = null,
            form = null,
            command = null,
            commandReady = false,
            loading = false,
            work = null,
            dialog = null,
        )

internal fun OvertimeActionState.withActionReads(
    request: AppResult<OvertimeSnapshot<OvertimeRequestView>?>,
    stored: AppResult<OvertimeCommandRecord?>,
): OvertimeActionState {
    if (restriction != null) return this
    val failed =
        listOfNotNull(
            (request as? AppResult.Failed)?.failure,
            (stored as? AppResult.Failed)?.failure,
        )
    failed.firstOrNull(::overtimeFailureInvalidatesCache)?.let {
        return withActionFailure(it)
    }
    val snapshot =
        (request as? AppResult.Success)?.value?.takeIf {
            it.value.selection.requestId == target.selection.requestId &&
                it.value.selection.employeeId == target.selection.employeeId &&
                it.value.selection.employmentAfter == target.selection.employmentAfter
        }
    val updated =
        copy(
                content = snapshot,
                loading = false,
                failure = failed.firstOrNull() ?: failure,
                commandReady = stored is AppResult.Success,
            )
            .withActionCommand((stored as? AppResult.Success)?.value)
    return if (updated.form == null && updated.command == null && updated.available)
        updated.copy(
            form = overtimeActionForm(target.kind, requireNotNull(snapshot).value.details.request)
        )
    else updated
}

internal fun OvertimeActionState.withActionCommand(
    record: OvertimeCommandRecord?
): OvertimeActionState {
    if (restriction != null) return this
    val previous = command
    if (
        record != null &&
            previous?.command?.id == record.command.id &&
            record.revision < previous.revision
    )
        return this
    return copy(command = record, dialog = dialog.takeIf { record == null })
}
