@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

fun LeavePageDto<LeaveRequestSummaryDto>.toRequests(
    employeeId: String,
    status: LeaveStatus?,
    after: String?,
): LeavePage<LeaveRequestSummary> {
    require(items.size <= 20)
    val rows =
        items.map { row ->
            Uuid.parse(row.id)
            Uuid.parse(row.employeeId)
            require(
                row.id != after &&
                    row.employeeId == employeeId &&
                    (status == null || row.status == status.name)
            )
            require(
                row.typeCode.isNotBlank() &&
                    row.typeCode.length <= 32 &&
                    row.typeName.isNotBlank() &&
                    row.typeName.length <= 200
            )
            require(
                row.version in 0..9_007_199_254_740_991L &&
                    row.from.length == 10 &&
                    row.until.length == 10 &&
                    row.submittedAt.length in 20..40
            )
            val from = LocalDate.parse(row.from)
            val until = LocalDate.parse(row.until)
            require(
                from.year in 1900..9998 &&
                    until.year in 1900..9998 &&
                    from.daysUntil(until) in 0..365
            )
            val charged = row.chargedDays.toLeaveDays(732)
            require(charged.halfDays > 0 && charged.halfDays <= (from.daysUntil(until) + 1) * 2)
            LeaveRequestSummary(
                row.id,
                employeeId,
                row.typeCode,
                row.typeName,
                from,
                until,
                charged,
                LeaveStatus.valueOf(row.status),
                Instant.parse(row.submittedAt),
                row.version,
            )
        }
    require(rows.distinctBy { it.id }.size == rows.size)
    require(
        rows.zipWithNext().all { (first, second) ->
            first.submittedAt > second.submittedAt ||
                (first.submittedAt == second.submittedAt && first.id > second.id)
        }
    )
    require(
        nextCursor == null ||
            (rows.size == 20 && nextCursor == rows.last().id && nextCursor != after)
    )
    return LeavePage(rows.toList(), nextCursor)
}

fun LeavePage<LeaveRequestSummary>.toRequestsDto() =
    LeavePageDto(
        items.map { row ->
            LeaveRequestSummaryDto(
                row.id,
                row.employeeId,
                row.typeCode,
                row.typeName,
                row.from.toString(),
                row.until.toString(),
                row.charged.toWireDays(),
                row.status.name,
                row.submittedAt.toString(),
                row.version,
            )
        },
        nextCursor,
    )
