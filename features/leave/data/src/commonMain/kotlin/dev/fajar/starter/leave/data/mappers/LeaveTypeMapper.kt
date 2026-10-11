@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate

fun LeavePageDto<LeaveTypeDto>.toTypes(selection: LeaveTypeSelection): LeavePage<LeaveTypeOption> {
    require(items.size <= 20)
    val types =
        items.map { row ->
            Uuid.parse(row.id)
            require(Regex("[A-Z][A-Z0-9_-]{0,31}").matches(row.code))
            require(
                row.name.isNotBlank() && row.name.length <= 200 && row.effectiveFrom.length == 10
            )
            val date = LocalDate.parse(row.effectiveFrom)
            require(date.year in 1900..2200 && date <= selection.asOf)
            require(
                row.version in 0..9_007_199_254_740_991L && row.appliedRevision in 0..row.version
            )
            selection.after?.let { require(row.code > it) }
            LeaveTypeOption(
                row.id,
                row.code,
                row.name,
                date,
                row.paid,
                row.allowPartialDays,
                row.attachmentRequired,
                row.active,
                row.version,
                row.appliedRevision,
            )
        }
    require(types.distinctBy { it.id }.size == types.size)
    require(types.zipWithNext().all { (first, next) -> first.code < next.code })
    require(
        nextCursor == null ||
            (types.size == 20 && nextCursor == types.last().code && nextCursor != selection.after)
    )
    return LeavePage(types.toList(), nextCursor)
}
