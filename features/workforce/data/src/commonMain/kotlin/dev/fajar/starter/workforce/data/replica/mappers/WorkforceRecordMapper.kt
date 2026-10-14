@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.replica.mappers

import dev.fajar.starter.workforce.data.attendance.mappers.*
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.replica.dto.*
import dev.fajar.starter.workforce.domain.entities.CalendarPeriod
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceRecord
import kotlin.uuid.Uuid
import kotlinx.datetime.*

fun AttendanceRecordDto.toAttendanceRecord(): WorkforceRecord.Attendance {
    Uuid.parse(id)
    Uuid.parse(employeeId)
    require(version in 0..9_007_199_254_740_991L)
    val date = LocalDate.parse(day.workDate)
    val mapped = listOf(day).toAttendanceDays(CalendarPeriod(date, date)).single()
    require(mapped.entries.isNotEmpty() || mapped.correction != null)
    return WorkforceRecord.Attendance(id, version, employeeId, mapped)
}

fun WorkScheduleRecordDto.toScheduleRecord(): WorkforceRecord.Schedule {
    Uuid.parse(id)
    Uuid.parse(employeeId)
    require(
        version in 0..9_007_199_254_740_991L &&
            assignment.revision == version &&
            assignment.days.size <= 7
    )
    return WorkforceRecord.Schedule(
        id,
        version,
        employeeId,
        LocalDate.parse(assignment.effectiveFrom),
        assignment.days
            .mapKeys { DayOfWeek.valueOf(it.key) }
            .mapValues { it.value.toShift() }
            .toMap(),
    )
}

fun RosterRecordDto.toRosterRecord(): WorkforceRecord.Roster {
    Uuid.parse(id)
    Uuid.parse(employeeId)
    require(version in 0..9_007_199_254_740_991L)
    return WorkforceRecord.Roster(
        id,
        version,
        employeeId,
        LocalDate.parse(workDate),
        shift?.toShift(),
    )
}

fun WorkHolidayRecordDto.toHolidayRecord(): WorkforceRecord.Holiday {
    Uuid.parse(id)
    require(version in 0..9_007_199_254_740_991L && name.isNotBlank() && name.length <= 200)
    return WorkforceRecord.Holiday(id, version, LocalDate.parse(workDate), name, active)
}

fun WorkforceRecordDto.toRecord(): WorkforceRecord =
    when (this) {
        is AttendanceRecordDto -> toAttendanceRecord()
        is WorkScheduleRecordDto -> toScheduleRecord()
        is RosterRecordDto -> toRosterRecord()
        is WorkHolidayRecordDto -> toHolidayRecord()
    }

fun WorkforceRecord.toDto(): WorkforceRecordDto =
    when (this) {
        is WorkforceRecord.Attendance -> AttendanceRecordDto(id, version, employeeId, day.toDto())
        is WorkforceRecord.Schedule ->
            WorkScheduleRecordDto(
                id,
                version,
                employeeId,
                WorkScheduleAssignmentDto(
                    effectiveFrom.toString(),
                    version,
                    days.mapKeys { it.key.name }.mapValues { it.value.toDto() },
                ),
            )
        is WorkforceRecord.Roster ->
            RosterRecordDto(id, version, employeeId, workDate.toString(), shift?.toDto())
        is WorkforceRecord.Holiday ->
            WorkHolidayRecordDto(id, version, workDate.toString(), name, active)
    }
