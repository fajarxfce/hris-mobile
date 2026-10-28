package dev.fajar.starter.workforce.data.overtime.working.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.data.overtime.working.dto.OvertimeActionWorkingCopyDto
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeAction
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.policies.validateOvertimeActionWorkingCopy
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime

fun OvertimeActionWorkingCopy.toDto() =
    OvertimeActionWorkingCopyDto(
        requestId,
        employeeId,
        action.name,
        revision,
        savedAt.toString(),
        input.requestVersion,
        input.actual?.startsAt?.toString(),
        input.actual?.endsAt?.toString(),
        input.actual?.breakMinutes,
        input.reason,
        hasEdits,
    )

fun OvertimeActionWorkingCopyDto.toWorkingCopy(): OvertimeActionWorkingCopy {
    require(listOf(startsAt, endsAt, breakMinutes).count { it != null } in setOf(0, 3))
    val input =
        OvertimeActionWorkingInput(
            requestVersion,
            if (startsAt == null) null
            else
                OvertimeActualWorkingInput(
                    LocalDateTime.parse(startsAt),
                    LocalDateTime.parse(requireNotNull(endsAt)),
                    requireNotNull(breakMinutes),
                ),
            reason,
        )
    return OvertimeActionWorkingCopy(
            requestId,
            employeeId,
            OvertimeAction.valueOf(action),
            input,
            revision,
            Instant.parse(savedAt),
            hasEdits,
        )
        .also { require(validateOvertimeActionWorkingCopy(it) is AppResult.Success) }
}
