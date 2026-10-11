package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveRequestSnapshot
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache

internal fun LeaveRequestState.withCachedRequest(
    result: AppResult<LeaveRequestSnapshot?>
): LeaveRequestState =
    when (result) {
        is AppResult.Failed -> copy(content = null, loading = false, cacheFailure = result.failure)
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
            )
        }
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
            )
    }
