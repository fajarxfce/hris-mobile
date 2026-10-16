@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

fun LeaveRequestDto.toDetails(selection: LeaveRequestSelection): LeaveRequestDetails {
    Uuid.parse(id)
    Uuid.parse(employeeId)
    ownerAccountId?.let(Uuid::parse)
    require(id == selection.requestId && version in 0..9_007_199_254_740_991L)
    require(employeeNumber.isNotBlank() && employeeNumber.length <= 32)
    require(employeeName.isNotBlank() && employeeName.length <= 200)
    require(reason.isNotBlank() && reason.length <= 1000 && submittedAt.length in 20..40)
    val submitted = Instant.parse(submittedAt)
    val requestPolicy = policy.toRequestPolicy()
    require(days.size in 1..366)
    val dates = days.map(LeaveDayDto::toLeaveDay)
    require(dates.zipWithNext().all { (first, next) -> first.date < next.date })
    require(dates.first().date.daysUntil(dates.last().date) <= 365)
    val charged = chargedDays.toLeaveDays(732)
    require(charged.halfDays == dates.sumOf { it.portion.halfDays })
    val status = LeaveStatus.valueOf(status)
    val changes = history.toChanges(selection.historyAfter, version, status, submitted)
    require(
        attachments.size <= 3 && attachments.distinctBy { it.revisionId }.size == attachments.size
    )
    val evidence =
        attachments.map { row ->
            Uuid.parse(row.documentId)
            Uuid.parse(row.revisionId)
            require(row.fileName.isNotBlank() && row.fileName.length <= 255)
            require(row.title == null || (row.title.isNotBlank() && row.title.length <= 200))
            require(row.mediaType.isNotBlank() && row.mediaType.length <= 200)
            require(row.size in 1..104_857_600 && Regex("[0-9a-f]{64}").matches(row.sha256))
            LeaveAttachment(
                row.documentId,
                row.revisionId,
                row.fileName,
                row.mediaType,
                row.size,
                row.sha256,
                row.title,
            )
        }
    require(availableActions.size <= LeaveAction.entries.size)
    return LeaveRequestDetails(
        id,
        employeeId,
        employeeNumber,
        employeeName,
        ownerAccountId,
        submitted,
        requestPolicy,
        dates.toList(),
        charged,
        reason,
        status,
        version,
        approval.toLeaveWorkflow(),
        cancellation?.toLeaveWorkflow(),
        changes,
        availableActions.map(LeaveAction::valueOf).toSet(),
        evidence.toList(),
    )
}

fun LeaveDayDto.toLeaveDay(): LeaveDay {
    require(workDate.length == 10 && startsAt.length in 20..40 && endsAt.length in 20..40)
    val date = LocalDate.parse(workDate)
    val start = Instant.parse(startsAt)
    val end = Instant.parse(endsAt)
    val portion = LeavePortion.valueOf(portion)
    require(date.year in 1900..9998 && end > start && end - start <= 2.days)
    require(plannedMinutes in 1..2880 && chargedMinutes in 1..plannedMinutes)
    require(chargedDays.toLeaveDays(2).halfDays == portion.halfDays)
    return LeaveDay(date, portion, start, end, plannedMinutes, chargedMinutes)
}

fun LeaveWorkflowDto.toLeaveWorkflow(): LeaveWorkflow {
    Uuid.parse(id)
    require(currentStep in 0..100 && version in 0..9_007_199_254_740_991L)
    return LeaveWorkflow(id, LeaveWorkflowStatus.valueOf(status), currentStep, version)
}

fun LeaveRequestDetails.toDto() =
    LeaveRequestDto(
        id = id,
        employeeId = employeeId,
        employeeNumber = employeeNumber,
        employeeName = employeeName,
        ownerAccountId = ownerAccountId,
        submittedAt = submittedAt.toString(),
        policy =
            LeaveRequestPolicyDto(
                policy.typeId,
                policy.code,
                policy.revision,
                policy.name,
                policy.paid,
                policy.allowPartialDays,
                policy.attachmentRequired,
            ),
        days =
            days.map { day ->
                LeaveDayDto(
                    day.date.toString(),
                    day.portion.name,
                    LeaveDays(day.portion.halfDays).toWireDays(),
                    day.startsAt.toString(),
                    day.endsAt.toString(),
                    day.plannedMinutes,
                    day.chargedMinutes,
                )
            },
        chargedDays = charged.toWireDays(),
        reason = reason,
        status = status.name,
        version = version,
        approval = approval.toDto(),
        cancellation = cancellation?.toDto(),
        history =
            LeavePageDto(
                history.items.map {
                    LeaveChangeDto(
                        it.version,
                        it.kind.name,
                        it.status.name,
                        it.recordedAt.toString(),
                        it.reason,
                    )
                },
                history.nextCursor,
            ),
        availableActions = availableActions.map { it.name }.toSet(),
        attachments =
            attachments.map {
                LeaveAttachmentDto(
                    it.documentId,
                    it.revisionId,
                    it.fileName,
                    it.mediaType,
                    it.size,
                    it.sha256,
                    it.title,
                )
            },
    )

fun LeaveWorkflow.toDto() = LeaveWorkflowDto(id, status.name, currentStep, version)
