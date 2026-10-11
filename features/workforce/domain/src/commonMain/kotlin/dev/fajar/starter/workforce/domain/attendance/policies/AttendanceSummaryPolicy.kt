package dev.fajar.starter.workforce.domain.attendance.policies

import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.WorkDay
import kotlin.time.Instant

/** Missing evidence is not a confirmed absence; future company dates do not enter metrics. */
fun summarizeAttendanceHistory(
    snapshot: AttendanceSnapshot,
    commands: List<AttendanceCommandRecord>,
    now: Instant,
): AttendanceSummary {
    val calendar = snapshot.calendar?.days.orEmpty().associateBy { it.date }
    val queued =
        commands
            .filter { it.command.employeeId == snapshot.employeeId }
            .groupBy { it.command.workDate }
    val days =
        snapshot.days
            .filter { it.date <= snapshot.directory.asOf }
            .map { day ->
                val outstanding =
                    queued[day.date].orEmpty().filter { record ->
                        day.entries.none { it.id == record.command.id }
                    }
                val scheduled =
                    day.entries
                        .firstOrNull {
                            it.status == AttendanceStatus.ACCEPTED &&
                                it.kind == AttendanceKind.CLOCK_IN
                        }
                        ?.schedule ?: day.entries.firstOrNull()?.schedule ?: calendar[day.date]
                val shiftEnded =
                    if (scheduled is WorkDay.Work) scheduled.endsAt <= now
                    else day.date < snapshot.directory.asOf
                val status =
                    when {
                        day.pendingCount > 0 -> AttendanceHistoryStatus.PENDING
                        day.correction != null ->
                            if (day.correction.clockIn == null) AttendanceHistoryStatus.ABSENT
                            else AttendanceHistoryStatus.PRESENT
                        outstanding.any { it.outcome !is AttendanceCommandOutcome.Rejected } ->
                            AttendanceHistoryStatus.SYNC_PENDING
                        day.incomplete && shiftEnded -> AttendanceHistoryStatus.INCOMPLETE
                        day.entries.any {
                            it.status == AttendanceStatus.ACCEPTED &&
                                it.kind == AttendanceKind.CLOCK_IN
                        } -> AttendanceHistoryStatus.PRESENT
                        outstanding.isNotEmpty() ||
                            day.entries.any { it.status == AttendanceStatus.REJECTED } ->
                            AttendanceHistoryStatus.REJECTED
                        scheduled is WorkDay.Off -> AttendanceHistoryStatus.DAY_OFF
                        scheduled is WorkDay.Work -> AttendanceHistoryStatus.NOT_RECORDED
                        else -> AttendanceHistoryStatus.NO_SCHEDULE
                    }
                AttendanceHistoryDay(day, status, outstanding)
            }
    return AttendanceSummary(
        presentDays =
            days.count { day ->
                day.attendance.correction?.let { it.clockIn != null }
                    ?: day.attendance.entries.any {
                        it.kind == AttendanceKind.CLOCK_IN && it.status == AttendanceStatus.ACCEPTED
                    }
            },
        absentDays =
            days.count {
                it.attendance.correction != null && it.attendance.correction.clockIn == null
            },
        reviewDays =
            days.count {
                it.status == AttendanceHistoryStatus.PENDING ||
                    it.status == AttendanceHistoryStatus.INCOMPLETE
            },
        acceptedMinutes = days.sumOf { it.attendance.acceptedMinutes },
        days = days,
    )
}
