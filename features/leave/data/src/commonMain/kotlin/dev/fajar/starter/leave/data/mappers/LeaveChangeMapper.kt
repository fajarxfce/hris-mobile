package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.LeaveChangeDto
import dev.fajar.starter.leave.data.dto.LeavePageDto
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Instant

/** Older history pages retain the current request header and must advance by revision. */
fun LeavePageDto<LeaveChangeDto>.toChanges(
    after: Long?,
    currentVersion: Long,
    currentStatus: LeaveStatus,
    submittedAt: Instant,
): LeavePage<LeaveChange> {
    require(items.size <= 10)
    val changes =
        items.map { row ->
            require(row.version in 0..currentVersion && (after == null || row.version < after))
            require(row.reason.length <= 1000 && row.recordedAt.length in 20..40)
            val recorded = Instant.parse(row.recordedAt)
            require(recorded >= submittedAt)
            LeaveChange(
                row.version,
                LeaveChangeKind.valueOf(row.kind),
                LeaveStatus.valueOf(row.status),
                recorded,
                row.reason,
            )
        }
    require(
        changes.zipWithNext().all { (first, next) ->
            first.version > next.version && first.recordedAt >= next.recordedAt
        }
    )
    if (after == null)
        require(
            changes.isNotEmpty() &&
                changes.first().version == currentVersion &&
                changes.first().status == currentStatus
        )
    require(
        nextCursor == null ||
            (changes.size == 10 &&
                changes.last().version > 0 &&
                nextCursor == changes.last().version.toString())
    )
    return LeavePage(changes.toList(), nextCursor)
}
