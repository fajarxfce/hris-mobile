package dev.fajar.starter.workforce.presentation.attendance

import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.attendanceWorkDate
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class AttendanceContent(
    val snapshot: AttendanceSnapshot,
    val employee: WorkforceEmployment?,
    val date: LocalDate,
    val today: AttendanceDay?,
    val shift: WorkDay.Work?,
    val commands: List<AttendanceCommandRecord>,
    val canClockIn: Boolean,
    val canClockOut: Boolean,
)

fun AttendanceView.toContent(now: Instant): AttendanceContent? {
    val snapshot = snapshot ?: return null
    val date = attendanceWorkDate(snapshot, now)
    val day = snapshot.days.singleOrNull { it.date == date }
    val employee = snapshot.directory.items.singleOrNull { it.id == snapshot.employeeId }
    val owned = commands.filter { it.command.employeeId == snapshot.employeeId }
    val recorded =
        day?.entries
            .orEmpty()
            .filter { it.status != AttendanceStatus.REJECTED }
            .map { it.kind }
            .toSet() + owned.filter { it.command.workDate == date }.map { it.command.kind }
    val enabled =
        "attendance.self.record" in snapshot.access.permissions &&
            employee?.status in
                setOf(WorkforceEmploymentStatus.ACTIVE, WorkforceEmploymentStatus.PROBATION) &&
            day?.correction == null
    return AttendanceContent(
        snapshot,
        employee,
        date,
        day,
        snapshot.calendar?.days?.filterIsInstance<WorkDay.Work>()?.singleOrNull { it.date == date },
        owned.filter { record ->
            snapshot.days.none { it.entries.any { entry -> entry.id == record.command.id } }
        },
        enabled && recorded.isEmpty(),
        enabled && AttendanceKind.CLOCK_IN in recorded && AttendanceKind.CLOCK_OUT !in recorded,
    )
}
