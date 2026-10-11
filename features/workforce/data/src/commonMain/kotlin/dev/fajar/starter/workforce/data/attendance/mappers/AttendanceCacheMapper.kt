package dev.fajar.starter.workforce.data.attendance.mappers

import dev.fajar.starter.workforce.data.attendance.dto.AttendanceSnapshotDto
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceSnapshot
import dev.fajar.starter.workforce.domain.attendance.policies.ATTENDANCE_CACHE_PERIOD
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun attendancePartition(access: WorkforceAccess) = "attendance/" + access.companyId

fun attendanceAssociatedData(access: WorkforceAccess, generation: String, purpose: String) =
    Json.encodeToString(
            listOf(
                "hris-attendance-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                purpose,
            )
        )
        .encodeToByteArray()

fun AttendanceSnapshot.toDto() =
    AttendanceSnapshotDto(
        directory.toDto(),
        employeeId,
        period.from.toString(),
        period.until.toString(),
        calendar?.toDto(),
        days.map { it.toDto() },
        access.permissions,
        access.sessionExpiresAt.toString(),
        fetchedAt.toString(),
        expiresAt.toString(),
        after,
    )

fun AttendanceSnapshotDto.toSnapshot(
    access: WorkforceAccess,
    generation: String,
): AttendanceSnapshot {
    require(permissions.size <= 256 && permissions.all { it.length in 1..128 })
    val owner =
        access.copy(
            permissions = permissions.toSet(),
            sessionExpiresAt = Instant.parse(sessionExpiresAt),
        )
    require(after == null || after.length in 1..32)
    val directory = directory.toDirectory(owner, after)
    val period = CalendarPeriod(LocalDate.parse(from), LocalDate.parse(until))
    require(
        period.from.daysUntil(period.until) == 6 &&
            period.from == directory.asOf.minus(5, DateTimeUnit.DAY)
    )
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(
        expiry > fetched &&
            expiry <= fetched + ATTENDANCE_CACHE_PERIOD &&
            expiry <= owner.sessionExpiresAt
    )
    require((employeeId == null) == (calendar == null))
    require(
        if (employeeId == null) directory.items.isEmpty() && days.isEmpty()
        else directory.items.any { it.id == employeeId }
    )
    return AttendanceSnapshot(
        owner,
        generation,
        directory,
        employeeId,
        period,
        calendar?.toCalendar(period),
        if (employeeId == null) emptyList() else days.toAttendanceDays(period),
        fetched,
        expiry,
        after,
    )
}
