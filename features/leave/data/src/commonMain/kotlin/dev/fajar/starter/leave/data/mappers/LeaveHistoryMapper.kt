package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*

fun leaveDetailId(requestId: String, component: String) = "detail/$requestId/$component"

fun leaveActivityComponent(after: Long?) = "activity/" + (after?.toString() ?: "latest")

fun leaveActivityId(requestId: String, after: Long?) =
    "activity/$requestId/" + (after?.toString() ?: "latest")

fun LeavePage<LeaveChange>.toHistoryDto() =
    LeavePageDto(
        items.map {
            LeaveChangeDto(
                it.version,
                it.kind.name,
                it.status.name,
                it.recordedAt.toString(),
                it.reason,
            )
        },
        nextCursor,
    )

fun LeaveHistoryPage.toDto() =
    LeaveHistoryPageDto(requestId, requestVersion, after, history.toHistoryDto())
