package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun initialLeaveCheckpoint(access: LeaveAccess, generation: String, now: Instant) =
    LeaveCheckpoint(
        access,
        generation,
        0,
        LeaveSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + LEAVE_FRESHNESS_PERIOD,
        null,
    )

internal fun advanceLeaveCheckpoint(
    previous: LeaveCheckpoint,
    page: LeaveSyncPage,
    now: Instant,
): LeaveCheckpoint {
    val caughtUp = page.phase == LeaveSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter = if (caughtUp) now + LEAVE_FRESHNESS_PERIOD else previous.refreshAfter,
        // Recurring idle work belongs to the scheduler; only unfinished acquisition waits here.
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}

fun sameStoredLeaveScope(first: LeaveAccess, second: LeaveAccess): Boolean =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions

fun validateStoredLeaveReplica(access: LeaveAccess, checkpoint: LeaveCheckpoint?): AppResult<Unit> =
    if (checkpoint != null && !sameStoredLeaveScope(access, checkpoint.access))
        leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)

/** Resolve a local selection without making directory filters acquisition policy. */
fun selectStoredLeave(
    directory: LeaveDirectory,
    selection: LeaveSelection,
): AppResult<LeaveSelection> {
    val valid = validateLeaveSelection(selection)
    if (valid is AppResult.Failed) return valid
    val year = selection.year ?: directory.asOf.year
    if (year !in 1900..2200) return leaveFailure("invalid_leave_year")
    val page = pageLeaveEmployments(directory, selection.employmentAfter)
    val employee =
        if (selection.employeeId == null)
            page.items.firstOrNull { it.status == LeaveEmploymentStatus.ACTIVE }
                ?: page.items.firstOrNull()
        else directory.items.firstOrNull { it.id == selection.employeeId }
    if (selection.employeeId != null && employee == null)
        return leaveFailure("employee_not_found", FailureKind.AccessDenied)
    return AppResult.Success(
        selection.copy(
            employeeId = employee?.id,
            year = year,
            // Older last-page snapshots used remote cursors; a replica starts at its own first
            // page.
            balanceAfter = selection.balanceAfter?.takeIf { it.startsWith("b/") },
            requestAfter = selection.requestAfter?.takeIf { it.startsWith("r/") },
        )
    )
}

fun pageLeaveEmployments(directory: LeaveDirectory, after: String?): LeaveDirectory {
    val items = directory.items.filter { after == null || it.employeeNumber > after }.take(21)
    return directory.copy(
        items = items.take(20),
        nextCursor = if (items.size > 20) items[19].employeeNumber else null,
    )
}
