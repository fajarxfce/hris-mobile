package dev.fajar.starter.workforce.presentation.attendance

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.attendanceWorkDate
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.presentation.attendance.formatters.attendanceOutcomeLabel
import dev.fajar.starter.workforce.presentation.attendance.formatters.attendanceStatusLabel
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
    val clockIn: AttendanceStamp?,
    val clockOut: AttendanceStamp?,
    val hasEmploymentChoice: Boolean,
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
    val outstanding =
        owned.filter { record ->
            snapshot.days.none { it.entries.any { entry -> entry.id == record.command.id } }
        }
    return AttendanceContent(
        snapshot,
        employee,
        date,
        day,
        snapshot.calendar?.days?.filterIsInstance<WorkDay.Work>()?.singleOrNull { it.date == date },
        outstanding,
        enabled && recorded.isEmpty(),
        enabled && AttendanceKind.CLOCK_IN in recorded && AttendanceKind.CLOCK_OUT !in recorded,
        attendanceStamp(
            AttendanceKind.CLOCK_IN,
            day,
            outstanding.filter { it.command.workDate == date },
        ),
        attendanceStamp(
            AttendanceKind.CLOCK_OUT,
            day,
            outstanding.filter { it.command.workDate == date },
        ),
        snapshot.directory.items.size > 1 ||
            snapshot.directory.nextCursor != null ||
            snapshot.after != null,
    )
}

/** Display the durable command until its server entry is available; a receipt is not approval. */
fun attendanceStamp(
    kind: AttendanceKind,
    day: AttendanceDay?,
    commands: List<AttendanceCommandRecord>,
): AttendanceStamp? {
    val correction = day?.correction
    if (correction != null) {
        val corrected =
            if (kind == AttendanceKind.CLOCK_IN) correction.clockIn else correction.clockOut
        return corrected?.let { AttendanceStamp(it, AppString.AttendanceCorrected) }
    }
    val command = commands.filter { it.command.kind == kind }.maxByOrNull { it.command.capturedAt }
    if (command != null)
        return AttendanceStamp(command.command.capturedAt, attendanceOutcomeLabel(command.outcome))
    val entries = day?.entries.orEmpty().filter { it.kind == kind }
    val entry =
        entries.filter { it.status != AttendanceStatus.REJECTED }.maxByOrNull { it.capturedAt }
            ?: entries.maxByOrNull { it.capturedAt }
            ?: return null
    return AttendanceStamp(entry.capturedAt, attendanceStatusLabel(entry.status))
}
