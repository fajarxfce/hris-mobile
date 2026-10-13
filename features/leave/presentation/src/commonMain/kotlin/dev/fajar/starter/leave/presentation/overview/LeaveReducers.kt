package dev.fajar.starter.leave.presentation.overview

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveOverview
import dev.fajar.starter.leave.domain.entities.LeaveSelection
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache

internal fun LeaveState.withCachedLeave(result: AppResult<LeaveOverview?>): LeaveState =
    when (result) {
        is AppResult.Failed ->
            copy(
                selection = filters,
                content = null,
                dialog = null,
                loading = false,
                cacheFailure = result.failure,
            )
        is AppResult.Success -> {
            val snapshot = result.value
            if (snapshot != null && selection != null && !selection.matches(snapshot)) this
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

internal fun LeaveState.withLeaveRefreshResult(result: AppResult<Unit>): LeaveState =
    when (result) {
        is AppResult.Failed -> {
            val invalidated = leaveFailureInvalidatesCache(result.failure)
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = if (invalidated) null else content,
                dialog = if (invalidated) null else dialog,
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

private fun LeaveSelection.matches(snapshot: LeaveOverview): Boolean =
    (employeeId == null || employeeId == snapshot.selection.employeeId) &&
        (year == null || year == snapshot.selection.year) &&
        employmentAfter == snapshot.selection.employmentAfter &&
        balanceAfter == snapshot.selection.balanceAfter &&
        status == snapshot.selection.status &&
        requestAfter == snapshot.selection.requestAfter
