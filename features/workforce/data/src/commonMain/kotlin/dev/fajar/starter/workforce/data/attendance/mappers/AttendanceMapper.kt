@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.attendance.mappers

import dev.fajar.starter.workforce.data.attendance.dto.*
import dev.fajar.starter.workforce.data.dto.WorkCalendarDto
import dev.fajar.starter.workforce.data.mappers.toCalendar
import dev.fajar.starter.workforce.data.mappers.toDto
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun List<AttendanceDayDto>.toAttendanceDays(period: CalendarPeriod): List<AttendanceDay> {
    require(
        period.from.daysUntil(period.until) in 0..30 &&
            size == period.from.daysUntil(period.until) + 1
    )
    val ids = mutableSetOf<String>()
    return mapIndexed { index, day ->
        val date = LocalDate.parse(day.workDate)
        require(
            date == period.from.plus(index, DateTimeUnit.DAY) &&
                day.entries.size <= 32 &&
                day.acceptedMinutes in 0..1440
        )
        val entries =
            day.entries.map { entry ->
                Uuid.parse(entry.id)
                require(
                    ids.add(entry.id) &&
                        entry.version in 0..1 &&
                        entry.issues.size <= 12 &&
                        entry.issues.all { it.matches(Regex("[A-Z_]{1,64}")) }
                )
                val schedule =
                    WorkCalendarDto(entry.schedule.originVersion, listOf(entry.schedule))
                        .toCalendar(CalendarPeriod(date, date))
                        .days
                        .single()
                AttendanceEntry(
                    entry.id,
                    AttendanceKind.valueOf(entry.kind),
                    Instant.parse(entry.capturedAt),
                    Instant.parse(entry.receivedAt),
                    entry.offline,
                    AttendanceStatus.valueOf(entry.status),
                    entry.issues.toSet(),
                    schedule,
                    entry.version,
                )
            }
        require(day.pendingCount == entries.count { it.status == AttendanceStatus.PENDING })
        val correction =
            day.correction?.let { raw ->
                Uuid.parse(raw.id)
                require(
                    raw.version in 0..9_007_199_254_740_991L &&
                        (raw.clockIn == null) == (raw.clockOut == null)
                )
                val start = raw.clockIn?.let(Instant::parse)
                val end = raw.clockOut?.let(Instant::parse)
                require(
                    start == null ||
                        (end != null && end > start && (end - start).inWholeMinutes <= 1440)
                )
                AttendanceCorrection(raw.id, start, end, raw.version)
            }
        AttendanceDay(
            date,
            entries.toList(),
            day.acceptedMinutes,
            day.pendingCount,
            day.incomplete,
            correction,
        )
    }
}

fun AttendanceCaptureWindowDto.toWindow(employee: String, device: String): AttendanceCaptureWindow {
    Uuid.parse(id)
    require(employeeId == employee && deviceId == device)
    val start = Instant.parse(issuedAt)
    val end = Instant.parse(expiresAt)
    require(end > start && end <= start + 2.minutes)
    return AttendanceCaptureWindow(id, employeeId, deviceId, start, end)
}

fun AttendanceReceiptDto.toReceipt(command: AttendanceCommand): AttendanceReceipt {
    require(id == command.id && version == 0L)
    return AttendanceReceipt(id, version)
}

fun AttendanceCommand.toCaptureDto() =
    AttendanceCaptureDto(
        id,
        workDate.toString(),
        kind.name,
        capturedAt.toString(),
        deviceId,
        windowId,
        offline,
        location?.let {
            AttendanceLocationDto(it.latitude, it.longitude, it.accuracyMeters, it.mocked)
        },
    )

fun AttendanceDay.toDto() =
    AttendanceDayDto(
        date.toString(),
        entries.map {
            val schedule = WorkCalendar(null, listOf(it.schedule)).toDto().days.single()
            AttendanceEntryDto(
                it.id,
                it.kind.name,
                it.capturedAt.toString(),
                it.receivedAt.toString(),
                it.offline,
                it.status.name,
                it.issues,
                schedule,
                it.version,
            )
        },
        acceptedMinutes,
        pendingCount,
        incomplete,
        correction?.let {
            AttendanceCorrectionDto(
                it.id,
                it.clockIn?.toString(),
                it.clockOut?.toString(),
                it.version,
            )
        },
    )
