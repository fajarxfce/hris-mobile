package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceDay
import dev.fajar.starter.workforce.domain.entities.CalendarShift
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** Owned facts and calendar inputs retain independent server identities and versions. */
sealed interface WorkforceRecord {
    val id: String
    val version: Long

    data class Attendance(
        override val id: String,
        override val version: Long,
        val employeeId: String,
        val day: AttendanceDay,
    ) : WorkforceRecord

    data class Schedule(
        override val id: String,
        override val version: Long,
        val employeeId: String,
        val effectiveFrom: LocalDate,
        val days: Map<DayOfWeek, CalendarShift>,
    ) : WorkforceRecord

    data class Roster(
        override val id: String,
        override val version: Long,
        val employeeId: String,
        val workDate: LocalDate,
        val shift: CalendarShift?,
    ) : WorkforceRecord

    data class Holiday(
        override val id: String,
        override val version: Long,
        val workDate: LocalDate,
        val name: String,
        val active: Boolean,
    ) : WorkforceRecord
}
