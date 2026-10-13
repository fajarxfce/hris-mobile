@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun validateLeaveRequestSelection(selection: LeaveRequestSelection): AppResult<Unit> =
    when {
        Uuid.parseOrNull(selection.requestId) == null -> leaveFailure("leave_request_not_found")
        selection.historyAfter != null && selection.historyAfter !in 0..9_007_199_254_740_991L ->
            leaveFailure("invalid_page")
        else -> AppResult.Success(Unit)
    }

fun validateLeaveActionSnapshot(
    access: LeaveAccess,
    snapshot: LeaveRequestSnapshot?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.authorizationExpiresAt <= now ->
            leaveFailure(access.authorizationExpiryCode, FailureKind.Unavailable)
        snapshot == null -> AppResult.Success(Unit)
        !sameLeaveScope(access, snapshot.access) ->
            leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.refreshAfter <= now ||
            snapshot.refreshAfter > snapshot.fetchedAt + LEAVE_FRESHNESS_PERIOD ->
            leaveFailure("client.leave_cache_expired", FailureKind.Unavailable)
        else -> AppResult.Success(Unit)
    }

/** Stored facts remain readable after freshness expires; commands validate current authority. */
fun validateStoredLeaveRequest(
    access: LeaveAccess,
    snapshot: LeaveRequestSnapshot?,
): AppResult<Unit> =
    if (snapshot != null && !sameLeaveScope(access, snapshot.access))
        leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)
