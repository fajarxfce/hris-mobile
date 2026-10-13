package dev.fajar.starter.leave.presentation.overview

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.LeaveOverview
import dev.fajar.starter.leave.domain.entities.LeaveSyncOutcome
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
            copy(
                content = snapshot,
                loading = false,
                cacheFailure = null,
                selection = snapshot?.selection ?: selection,
            )
        }
    }

internal fun LeaveState.withLeaveRefreshResult(result: AppResult<LeaveSyncOutcome>): LeaveState =
    when (result) {
        is AppResult.Failed -> {
            val invalidated = leaveFailureInvalidatesCache(result.failure)
            copy(
                refreshing = false,
                syncPending = false,
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
                loading = false,
                syncPending = !result.value.caughtUp,
                cacheFailure = if (result.value.caughtUp) null else cacheFailure,
                refreshFailure = if (result.value.caughtUp) null else refreshFailure,
            )
    }
