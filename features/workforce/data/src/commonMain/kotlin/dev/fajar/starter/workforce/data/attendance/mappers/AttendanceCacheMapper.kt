package dev.fajar.starter.workforce.data.attendance.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.data.attendance.dto.AttendanceSnapshotDto
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.ATTENDANCE_FRESHNESS_PERIOD
import dev.fajar.starter.workforce.domain.attendance.policies.attendancePeriod
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun attendancePartition(
    access: WorkforceAccess,
    window: AttendanceWindow = AttendanceWindow.RECENT,
) =
    when (window) {
        AttendanceWindow.RECENT -> "attendance/"
        AttendanceWindow.MONTHLY -> "attendance-history/"
    } + access.companyId

fun attendanceAssociatedData(
    access: WorkforceAccess,
    generation: String,
    purpose: String,
    window: AttendanceWindow = AttendanceWindow.RECENT,
) =
    Json.encodeToString(
            listOf(
                if (window == AttendanceWindow.RECENT) "hris-attendance-v1"
                else "hris-attendance-history-v1",
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
        refreshAfter.toString(),
        after,
    )

fun AttendanceSnapshotDto.toSnapshot(
    access: WorkforceAccess,
    generation: String,
    window: AttendanceWindow = AttendanceWindow.RECENT,
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
    val requested =
        AttendanceSelection(
            window = window,
            from = if (window == AttendanceWindow.MONTHLY) period.from else null,
        )
    require((attendancePeriod(directory.asOf, requested) as? AppResult.Success)?.value == period)
    val fetched = Instant.parse(fetchedAt)
    val expiry = Instant.parse(expiresAt)
    require(expiry > fetched && expiry <= fetched + ATTENDANCE_FRESHNESS_PERIOD)
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
