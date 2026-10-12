@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.workforce.data.mappers.toWorkDay
import dev.fajar.starter.workforce.data.overtime.dto.OvertimePlanningReviewDto
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun OvertimePlanningReviewDto.toReview(
    selection: OvertimePlanningSelection
): OvertimePlanningReview {
    require(
        Uuid.parse(employee.id).toString() == employee.id && employee.id == selection.employeeId
    )
    require(employee.employeeNumber.isNotBlank() && employee.employeeNumber.length <= 32)
    require(employee.legalName.isNotBlank() && employee.legalName.length <= 200)
    require(employee.version in 0..9_007_199_254_740_991L)
    val date = LocalDate.parse(workDate)
    val today = LocalDate.parse(companyDate)
    require(date == selection.workDate && date.year in 2000..2100 && today.year in 2000..2100)
    require(workDate == date.toString() && companyDate == today.toString())
    require(timezone.length in 1..100 && Regex("[0-9a-f]{64}").matches(revision))
    TimeZone.of(timezone)
    val day = schedule.toWorkDay(date)
    require(day !is WorkDay.Unassigned)
    require(day !is WorkDay.Work || day.shift.timezone == timezone)
    return OvertimePlanningReview(
        WorkforceEmployment(
            employee.id,
            employee.employeeNumber,
            WorkforceEmploymentStatus.valueOf(employee.status),
            employee.version,
        ),
        date,
        today,
        timezone,
        day,
        revision,
    )
}
