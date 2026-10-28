package dev.fajar.starter.leave.data.working.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.working.dto.LeaveWorkingCopyDto
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.working.entities.*
import dev.fajar.starter.leave.domain.working.policies.validateLeaveWorkingCopy
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

fun LeaveWorkingCopyDto.toWorkingCopy(): LeaveWorkingCopy {
    require(format == 1 && from.length == 10 && until.length == 10 && savedAt.length in 20..40)
    val value =
        LeaveWorkingCopy(
            employeeId,
            LeaveWorkingInput(
                LocalDate.parse(from),
                LocalDate.parse(until),
                type?.let {
                    require(it.effectiveFrom.length == 10)
                    LeaveTypeOption(
                        it.id,
                        it.code,
                        it.name,
                        LocalDate.parse(it.effectiveFrom),
                        it.paid,
                        it.allowPartialDays,
                        it.attachmentRequired,
                        it.active,
                        it.version,
                        it.appliedRevision,
                    )
                },
                LeavePortion.valueOf(portion),
                reason,
                attachments.map {
                    LeaveAttachment(
                        it.documentId,
                        it.revisionId,
                        it.fileName,
                        it.mediaType,
                        it.size,
                        it.sha256,
                        it.title,
                    )
                },
            ),
            revision,
            Instant.parse(savedAt),
            hasEdits,
        )
    require(validateLeaveWorkingCopy(value) is AppResult.Success)
    return value
}

fun LeaveWorkingCopy.toDto() =
    LeaveWorkingCopyDto(
        employeeId,
        input.from.toString(),
        input.until.toString(),
        input.type?.let {
            LeaveTypeDto(
                it.id,
                it.code,
                it.name,
                it.effectiveFrom.toString(),
                it.paid,
                it.allowPartialDays,
                it.attachmentRequired,
                it.active,
                it.version,
                it.appliedRevision,
            )
        },
        input.portion.name,
        input.reason,
        input.attachments.map {
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
        revision,
        savedAt.toString(),
        hasEdits = hasEdits,
    )
