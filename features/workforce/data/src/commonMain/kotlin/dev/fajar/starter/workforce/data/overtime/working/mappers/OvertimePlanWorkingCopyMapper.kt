package dev.fajar.starter.workforce.data.overtime.working.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.data.overtime.working.dto.OvertimePlanWorkingCopyDto
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.policies.validateOvertimePlanWorkingCopy
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

fun OvertimePlanWorkingCopy.toDto() =
    OvertimePlanWorkingCopyDto(
        employeeId,
        revision,
        savedAt.toString(),
        input.workDate.toString(),
        input.startDate.toString(),
        input.endDate.toString(),
        input.startsAt?.toString(),
        input.endsAt?.toString(),
        input.breakMinutes,
        input.reason,
    )

fun OvertimePlanWorkingCopyDto.toWorkingCopy() =
    OvertimePlanWorkingCopy(
            employeeId,
            OvertimePlanWorkingInput(
                LocalDate.parse(workDate),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                startsAt?.let(LocalTime::parse),
                endsAt?.let(LocalTime::parse),
                breakMinutes,
                reason,
            ),
            revision,
            Instant.parse(savedAt),
        )
        .also { require(validateOvertimePlanWorkingCopy(it) is AppResult.Success) }
