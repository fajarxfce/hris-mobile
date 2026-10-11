package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache

internal fun LeaveRequestState.withCachedRequest(
    result: AppResult<LeaveRequestSnapshot?>
): LeaveRequestState =
    when (result) {
        is AppResult.Failed ->
            copy(
                content = null,
                loading = false,
                cacheFailure = result.failure,
                action = action.copy(form = null, command = null, ready = false),
            )
        is AppResult.Success -> {
            val snapshot = result.value
            if (snapshot != null && selection != null && snapshot.selection != selection) this
            else
                copy(
                    content = snapshot,
                    loading = false,
                    cacheFailure = null,
                    selection =
                        if (refreshFailure != null && selection == null) snapshot?.selection
                        else selection,
                    action =
                        action.copy(
                            form = action.form?.takeIf { it.version == snapshot?.request?.version }
                        ),
                )
        }
    }

internal fun LeaveRequestState.withRequestRefreshResult(
    result: AppResult<Unit>
): LeaveRequestState =
    when (result) {
        is AppResult.Failed -> {
            val invalidated = leaveFailureInvalidatesCache(result.failure)
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = if (invalidated) null else content,
                selection = selection ?: content?.selection?.takeUnless { invalidated },
                action =
                    if (invalidated) action.copy(form = null, command = null, ready = false)
                    else action,
            )
        }
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
                action = action.copy(failure = null),
            )
    }

internal fun LeaveRequestState.withLeaveActionFailure(failure: Failure): LeaveRequestState {
    val invalidated = leaveFailureInvalidatesCache(failure)
    return copy(
        content = if (invalidated) null else content,
        loading = if (invalidated) false else loading,
        action =
            action.copy(
                failure = failure,
                form = if (invalidated) null else action.form,
                command = if (invalidated) null else action.command,
                ready = !invalidated && action.ready,
            ),
    )
}

internal fun LeaveRequestState.withObservedLeaveAction(
    result: AppResult<LeaveCommandRecord?>
): LeaveRequestState =
    when (result) {
        is AppResult.Failed ->
            withLeaveActionFailure(result.failure).let {
                it.copy(action = it.action.copy(command = null, ready = false, form = null))
            }
        is AppResult.Success -> {
            val record = result.value
            val current = action.command
            if (
                record != null &&
                    current?.command?.id == record.command.id &&
                    record.revision < current.revision
            )
                this
            else {
                copy(
                    action =
                        action.copy(
                            command = record,
                            ready = true,
                            form = action.form.takeIf { record == null },
                        )
                )
            }
        }
    }
