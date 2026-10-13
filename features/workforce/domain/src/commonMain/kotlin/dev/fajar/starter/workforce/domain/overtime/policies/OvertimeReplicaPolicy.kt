package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun initialOvertimeCheckpoint(access: WorkforceAccess, generation: String, now: Instant) =
    OvertimeCheckpoint(
        access,
        generation,
        0,
        OvertimeSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + OVERTIME_FRESHNESS_PERIOD,
        null,
    )

internal fun advanceOvertimeCheckpoint(
    previous: OvertimeCheckpoint,
    page: OvertimeSyncPage,
    now: Instant,
): OvertimeCheckpoint {
    val caughtUp =
        page.phase == OvertimeSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter = if (caughtUp) now + OVERTIME_FRESHNESS_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}

fun sameStoredOvertimeScope(first: WorkforceAccess, second: WorkforceAccess): Boolean =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions

fun validateStoredOvertimeReplica(
    access: WorkforceAccess,
    checkpoint: OvertimeCheckpoint?,
): AppResult<Unit> =
    if (checkpoint != null && !sameStoredOvertimeScope(access, checkpoint.access))
        overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)

fun selectStoredOvertime(
    directory: WorkforceDirectory,
    selection: OvertimeSelection,
): AppResult<OvertimeSelection> {
    val valid = validateOvertimeSelection(selection)
    if (valid is AppResult.Failed) return valid
    val page = pageOvertimeEmployments(directory, selection.employmentAfter)
    val employee =
        if (selection.employeeId == null)
            page.items.firstOrNull {
                it.status in
                    setOf(WorkforceEmploymentStatus.ACTIVE, WorkforceEmploymentStatus.PROBATION)
            } ?: page.items.firstOrNull()
        else directory.items.firstOrNull { it.id == selection.employeeId }
    if (selection.employeeId != null && employee == null)
        return overtimeFailure("employee_not_found", FailureKind.AccessDenied)
    val selected =
        selection.copy(
            employeeId = employee?.id,
            period = selection.period ?: overtimeMonth(directory.asOf),
            // The preceding remote UUID cursor has a different order; start the local page afresh.
            requestAfter = selection.requestAfter?.takeIf { it.startsWith("r/") },
        )
    val resolved = validateOvertimeSelection(selected)
    return if (resolved is AppResult.Failed) resolved else AppResult.Success(selected)
}

fun pageOvertimeEmployments(directory: WorkforceDirectory, after: String?): WorkforceDirectory {
    val rows = directory.items.filter { after == null || it.employeeNumber > after }.take(21)
    return directory.copy(
        items = rows.take(20),
        nextCursor = if (rows.size > 20) rows[19].employeeNumber else null,
    )
}
