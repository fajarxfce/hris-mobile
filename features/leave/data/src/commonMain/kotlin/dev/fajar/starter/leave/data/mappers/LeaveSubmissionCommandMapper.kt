@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.Json

fun leaveSubmissionPartition(access: LeaveAccess) = "hris.leave-submission/" + access.companyId

fun LeaveSubmissionCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

fun leaveSubmissionAssociatedData(
    access: LeaveAccess,
    id: String,
    employeeId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-leave-submission-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                employeeId,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

fun LeaveSubmissionCommand.toDto() =
    LeaveSubmissionCommandDto(
        id,
        accountId,
        audience,
        companyId,
        requestId,
        selection.employeeId,
        selection.typeId,
        selection.days.map { LeaveRequestedDayDto(it.date.toString(), it.portion.name) },
        previewRevision,
        typeName,
        charged.halfDays,
        reason,
        attachmentRevisionIds.toList(),
        createdAt.toString(),
    )

fun LeaveSubmissionCommandDto.toSubmissionCommand(): LeaveSubmissionCommand {
    listOf(id, accountId, companyId, requestId, employeeId, typeId).forEach {
        require(Uuid.parse(it).toString() == it)
    }
    require(reason.isNotBlank() && reason.length <= 1_000 && reason == reason.trim())
    require(
        typeName.isNotBlank() &&
            typeName.length <= 200 &&
            previewRevision.matches(Regex("[0-9a-f]{64}"))
    )
    require(days.size in 1..366)
    val selected =
        days.map {
            require(it.workDate.length == 10)
            val date = LocalDate.parse(it.workDate)
            require(date.year in 1900..2200)
            LeaveRequestedDay(date, LeavePortion.valueOf(it.portion))
        }
    require(selected.zipWithNext().all { (first, next) -> first.date < next.date })
    require(selected.first().date.daysUntil(selected.last().date) <= 365)
    require(chargedHalfDays in 1..selected.sumOf { it.portion.halfDays })
    require(
        attachmentRevisionIds.size <= 3 &&
            attachmentRevisionIds.distinct().sorted() == attachmentRevisionIds
    )
    attachmentRevisionIds.forEach { require(Uuid.parse(it).toString() == it) }
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    return LeaveSubmissionCommand(
        id,
        accountId,
        audience,
        companyId,
        requestId,
        LeaveDraftSelection(employeeId, typeId, selected.toList()),
        previewRevision,
        typeName,
        LeaveDays(chargedHalfDays),
        reason,
        attachmentRevisionIds.toList(),
        created,
    )
}

fun LeaveCommandOutcomeDto.toSubmissionRecord(
    command: LeaveSubmissionCommand,
    raw: CommandRecord,
    access: LeaveAccess,
): LeaveSubmissionRecord {
    require(command.belongsTo(access))
    require(
        raw.partition == leaveSubmissionPartition(access) &&
            raw.id == command.id &&
            raw.resourceId == command.selection.employeeId &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L)
    return LeaveSubmissionRecord(command, raw.revision, attempts, toOutcome(0))
}

fun LeaveSubmissionCommand.toSubmissionRequest(): LeaveSubmissionRequest {
    // The same bounded protocol invariants protect storage restoration and direct transport calls.
    require(toDto().toSubmissionCommand() == this)
    return LeaveSubmissionRequest(
        requestId,
        selection.employeeId,
        selection.typeId,
        selection.days.map { LeaveRequestedDayDto(it.date.toString(), it.portion.name) },
        reason,
        attachmentRevisionIds,
        previewRevision,
    )
}

fun LeaveActionReceiptDto.toSubmissionReceipt(
    command: LeaveSubmissionCommand
): LeaveSubmissionReceipt {
    require(id == command.requestId && version == 0L)
    return LeaveSubmissionReceipt(id, version)
}
