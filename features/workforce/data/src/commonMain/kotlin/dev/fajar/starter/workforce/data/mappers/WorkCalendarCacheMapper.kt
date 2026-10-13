package dev.fajar.starter.workforce.data.mappers

import dev.fajar.starter.workforce.data.dto.WorkCalendarSnapshotDto
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.WORK_CALENDAR_FRESHNESS_PERIOD
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun workCalendarPartition(access: WorkforceAccess) = "work-calendar/" + access.companyId

fun workCalendarAssociatedData(
    access: WorkforceAccess,
    generation: String,
    purpose: String,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-work-calendar-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                purpose,
            )
        )
        .encodeToByteArray()

fun WorkCalendarSnapshot.toDto() =
    WorkCalendarSnapshotDto(
        directory.toDto(),
        selection.employeeId,
        period.from.toString(),
        selection.after,
        calendar?.toDto(),
        access.permissions,
        access.sessionExpiresAt.toString(),
        fetchedAt.toString(),
        refreshAfter.toString(),
    )

fun WorkCalendarSnapshotDto.toSnapshot(
    access: WorkforceAccess,
    generation: String,
): WorkCalendarSnapshot {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    require(after == null || (after.isNotBlank() && after.length <= 32))
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
        )
    val directory = directory.toDirectory(owner, after)
    val start = LocalDate.parse(from)
    require(
        start.year in 1900..9998 &&
            start.dayOfWeek == DayOfWeek.MONDAY &&
            directory.asOf.daysUntil(start) in -366..366
    )
    val period = CalendarPeriod(start, start.plus(6, DateTimeUnit.DAY))
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(expiry > fetched && expiry <= fetched + WORK_CALENDAR_FRESHNESS_PERIOD)
    require((employeeId == null) == (calendar == null))
    require(
        if (employeeId == null) directory.items.isEmpty()
        else directory.items.any { it.id == employeeId }
    )
    return WorkCalendarSnapshot(
        owner,
        generation,
        directory,
        WorkCalendarSelection(employeeId, start, after),
        period,
        calendar?.toCalendar(period),
        fetched,
        expiry,
    )
}
