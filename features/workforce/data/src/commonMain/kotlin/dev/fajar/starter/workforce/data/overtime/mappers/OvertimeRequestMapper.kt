@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.toSummary
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun OvertimeIntervalDto.toInterval(): OvertimeInterval {
    require(startsAt.length <= 40 && endsAt.length <= 40)
    val start = Instant.parse(startsAt)
    val end = Instant.parse(endsAt)
    require(
        start.nanosecondsOfSecond == 0 &&
            end.nanosecondsOfSecond == 0 &&
            start.epochSeconds % 60 == 0L &&
            end.epochSeconds % 60 == 0L
    )
    require(breakMinutes in 0..120 && workedMinutes in 1..720)
    require(
        end > start &&
            end - start <= 720.minutes &&
            end - start == (workedMinutes + breakMinutes).minutes
    )
    return OvertimeInterval(start, end, breakMinutes, workedMinutes)
}

fun OvertimeRequestDto.toRequest(): OvertimeRequest {
    listOfNotNull(id, employeeId, requesterAccountId, authorId, submittedBy, approvalId).forEach {
        require(Uuid.parse(it).toString() == it)
    }
    require(
        employeeNumber.isNotBlank() &&
            employeeNumber.length <= 32 &&
            employeeName.isNotBlank() &&
            employeeName.length <= 200
    )
    require(
        reason.isNotBlank() &&
            reason.length <= 1_000 &&
            version in 0..999 &&
            timezone.length in 1..100
    )
    TimeZone.of(timezone)
    val date = LocalDate.parse(workDate)
    require(date.year in 2000..2100)
    val day = schedule.toWorkDay(date)
    require(day !is WorkDay.Unassigned)
    val planned = requested.toInterval()
    val recorded = actual?.toInterval()
    val status = OvertimeStatus.valueOf(status)
    val terminal =
        status in setOf(OvertimeStatus.APPROVED, OvertimeStatus.REJECTED, OvertimeStatus.WITHDRAWN)
    require(
        (recorded == null) == (submittedBy == null) &&
            (recorded == null) == (submittedAt == null) &&
            (recorded == null) == (approvalId == null)
    )
    require(terminal == (decidedAt != null))
    require(
        if (status == OvertimeStatus.PLANNED) recorded == null && version == 0L else version > 0
    )
    if (status in setOf(OvertimeStatus.PENDING, OvertimeStatus.APPROVED, OvertimeStatus.REJECTED))
        require(recorded != null)
    require(
        approvedMinutes == if (status == OvertimeStatus.APPROVED) recorded?.workedMinutes else 0
    )
    if (recorded != null)
        require(
            recorded.startsAt >= planned.startsAt &&
                recorded.endsAt <= planned.endsAt &&
                recorded.workedMinutes <= planned.workedMinutes
        )
    listOfNotNull(createdAt, submittedAt, decidedAt).forEach { require(it.length <= 40) }
    return OvertimeRequest(
        id,
        employeeId,
        employeeNumber,
        employeeName,
        requesterAccountId,
        authorId,
        Instant.parse(createdAt),
        date,
        timezone,
        day,
        planned,
        reason,
        status,
        recorded,
        submittedBy,
        submittedAt?.let(Instant::parse),
        approvalId,
        approvedMinutes,
        decidedAt?.let(Instant::parse),
        version,
    )
}

fun OvertimePageDto<OvertimeRequestDto>.toRequests(
    employeeId: String,
    period: CalendarPeriod,
    status: OvertimeStatus?,
    after: String?,
): OvertimePage<OvertimeSummary> {
    require(items.size <= 20)
    val mapped = items.map { it.toRequest().toSummary() }
    return validateOvertimePage(mapped, nextCursor, employeeId, period, status, after)
}

fun validateOvertimePage(
    items: List<OvertimeSummary>,
    nextCursor: String?,
    employeeId: String,
    period: CalendarPeriod,
    status: OvertimeStatus?,
    after: String?,
): OvertimePage<OvertimeSummary> {
    require(items.size <= 20)
    var previous = after ?: ""
    items.forEach { item ->
        require(
            item.id > previous &&
                item.employeeId == employeeId &&
                item.workDate >= period.from &&
                item.workDate <= period.until &&
                (status == null || item.status == status)
        )
        previous = item.id
    }
    require(nextCursor == null || (items.size == 20 && nextCursor == items.last().id))
    return OvertimePage(items.toList(), nextCursor)
}

fun OvertimeInterval.toDto() =
    OvertimeIntervalDto(startsAt.toString(), endsAt.toString(), breakMinutes, workedMinutes)

fun OvertimeRequest.toDto() =
    OvertimeRequestDto(
        id,
        employeeId,
        employeeNumber,
        employeeName,
        requesterAccountId,
        authorId,
        createdAt.toString(),
        workDate.toString(),
        timezone,
        schedule.toDto(),
        requested.toDto(),
        reason,
        status.name,
        actual?.toDto(),
        submittedBy,
        submittedAt?.toString(),
        approvalId,
        approvedMinutes,
        decidedAt?.toString(),
        version,
    )
