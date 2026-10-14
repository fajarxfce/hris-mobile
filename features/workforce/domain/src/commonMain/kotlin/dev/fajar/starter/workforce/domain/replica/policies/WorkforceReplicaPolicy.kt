package dev.fajar.starter.workforce.domain.replica.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.*

fun initialWorkforceCheckpoint(access: WorkforceAccess, generation: String, now: Instant) =
    WorkforceCheckpoint(
        access,
        generation,
        0,
        WorkforceSyncPhase.BOOTSTRAP,
        null,
        false,
        null,
        now + WORK_CALENDAR_FRESHNESS_PERIOD,
        null,
    )

internal fun advanceWorkforceCheckpoint(
    previous: WorkforceCheckpoint,
    page: WorkforceSyncPage,
    now: Instant,
): WorkforceCheckpoint {
    val caughtUp =
        page.phase == WorkforceSyncPhase.CHANGES && !page.hasMore && !page.pendingPublication
    return previous.copy(
        revision = previous.revision + 1,
        phase = page.phase,
        cursor = page.cursor,
        published = previous.published || caughtUp,
        refreshedAt = if (caughtUp) now else previous.refreshedAt,
        refreshAfter =
            if (caughtUp) now + WORK_CALENDAR_FRESHNESS_PERIOD else previous.refreshAfter,
        pollAt = if (page.hasMore || caughtUp) null else now + page.pollAfterSeconds.seconds,
    )
}

fun sameStoredWorkforceScope(first: WorkforceAccess, second: WorkforceAccess): Boolean =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions

fun validateStoredWorkforceReplica(
    access: WorkforceAccess,
    checkpoint: WorkforceCheckpoint?,
): AppResult<Unit> =
    if (checkpoint != null && !sameStoredWorkforceScope(access, checkpoint.access))
        calendarFailure("client.calendar_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)

/** The local company date advances with the clock, without advancing data freshness. */
fun currentWorkforceDirectory(directory: WorkforceDirectory, now: Instant): WorkforceDirectory =
    directory.copy(asOf = now.toLocalDateTime(TimeZone.of(directory.timezone)).date)

fun pageWorkforceEmployments(directory: WorkforceDirectory, after: String?): WorkforceDirectory {
    val rows = directory.items.filter { after == null || it.employeeNumber > after }.take(21)
    return directory.copy(
        items = rows.take(20),
        nextCursor = if (rows.size > 20) rows[19].employeeNumber else null,
    )
}

fun selectWorkforceEmployee(
    directory: WorkforceDirectory,
    employeeId: String?,
    after: String?,
): AppResult<WorkforceEmployment?> {
    val page = pageWorkforceEmployments(directory, after)
    val employee =
        if (employeeId == null)
            page.items.firstOrNull {
                it.status in
                    setOf(WorkforceEmploymentStatus.ACTIVE, WorkforceEmploymentStatus.PROBATION)
            } ?: page.items.firstOrNull()
        else directory.items.firstOrNull { it.id == employeeId }
    return if (employeeId != null && employee == null)
        calendarFailure("employee_not_found", FailureKind.AccessDenied)
    else AppResult.Success(employee)
}

fun workforceRecordEmployee(record: WorkforceRecord): String? =
    when (record) {
        is WorkforceRecord.Attendance -> record.employeeId
        is WorkforceRecord.Schedule -> record.employeeId
        is WorkforceRecord.Roster -> record.employeeId
        is WorkforceRecord.Holiday -> null
    }
