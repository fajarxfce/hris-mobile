package dev.fajar.starter.workforce.domain.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.summarizeAttendanceHistory
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.*

class AttendanceSummaryTest {
    private val f = AttendanceFixture()
    private val start = LocalDate(2026, 10, 1)
    private val shift =
        CalendarShift(
            "shift",
            0,
            "DAY",
            "Day shift",
            LocalTime(9, 0),
            LocalTime(17, 0),
            0,
            "Asia/Jakarta",
            CalendarWorkMode.ONSITE,
        )

    @Test
    fun absencesAreExplicitAndUnrecordedOffUnassignedAndFutureDaysRemainDistinct() {
        val schedules =
            (0..6).map { index ->
                val date = start.plus(index, DateTimeUnit.DAY)
                when (index) {
                    3 -> WorkDay.Off(date, CalendarOrigin.PATTERN, 0, null)
                    6 -> WorkDay.Unassigned(date)
                    else ->
                        WorkDay.Work(
                            date,
                            shift,
                            f.now - 48.hours,
                            f.now - 40.hours,
                            480,
                            CalendarOrigin.PATTERN,
                            0,
                        )
                }
            }
        val days =
            schedules.mapIndexed { index, schedule ->
                val entries =
                    if (index in setOf(0, 1, 2, 5))
                        listOf(
                            entry(
                                "in-$index",
                                schedule,
                                if (index == 2) AttendanceStatus.PENDING
                                else AttendanceStatus.ACCEPTED,
                            )
                        )
                    else emptyList()
                AttendanceDay(
                    schedule.date,
                    entries,
                    if (index == 0) 480 else 0,
                    if (index == 2) 1 else 0,
                    index == 5,
                    if (index == 1) AttendanceCorrection("absence", null, null, 0) else null,
                )
            } +
                AttendanceDay(
                    LocalDate(2026, 10, 13),
                    emptyList(),
                    480,
                    0,
                    false,
                    AttendanceCorrection("future", f.now, f.now + 8.hours, 0),
                )
        val snapshot =
            f.initial.copy(
                period = CalendarPeriod(start, LocalDate(2026, 10, 31)),
                calendar = WorkCalendar(null, schedules),
                days = days,
            )
        val summary = summarizeAttendanceHistory(snapshot, emptyList(), f.now)
        assertEquals(2, summary.presentDays)
        assertEquals(1, summary.absentDays)
        assertEquals(2, summary.reviewDays)
        assertEquals(480, summary.acceptedMinutes)
        assertEquals(
            listOf(
                AttendanceHistoryStatus.PRESENT,
                AttendanceHistoryStatus.ABSENT,
                AttendanceHistoryStatus.PENDING,
                AttendanceHistoryStatus.DAY_OFF,
                AttendanceHistoryStatus.NOT_RECORDED,
                AttendanceHistoryStatus.INCOMPLETE,
                AttendanceHistoryStatus.NO_SCHEDULE,
            ),
            summary.days.map { it.status },
        )
    }

    @Test
    fun localCommandsAppearWithoutBecomingAcceptedTimeOrAnotherEmployeesHistory() {
        val date = f.base.directory.asOf
        val command =
            AttendanceCommand(
                "queued",
                f.access.accountId,
                f.access.audience,
                f.access.companyId,
                f.base.employee.id,
                date,
                AttendanceKind.CLOCK_IN,
                f.now,
                "device",
                null,
                true,
                null,
            )
        val queued = AttendanceCommandRecord(command)
        val summary =
            summarizeAttendanceHistory(
                f.initial,
                listOf(
                    queued,
                    AttendanceCommandRecord(command.copy(id = "foreign", employeeId = "another")),
                ),
                f.now,
            )
        val today = summary.days.single { it.attendance.date == date }
        assertEquals(AttendanceHistoryStatus.SYNC_PENDING, today.status)
        assertEquals(listOf(queued), today.commands)
        assertEquals(0, summary.presentDays)
        assertEquals(0, summary.reviewDays)
        assertEquals(0, summary.acceptedMinutes)
        val rejected =
            queued.copy(
                outcome =
                    AttendanceCommandOutcome.Rejected(Failure(FailureKind.Validation, "fixture"))
            )
        assertEquals(
            AttendanceHistoryStatus.REJECTED,
            summarizeAttendanceHistory(f.initial, listOf(rejected), f.now).days.last().status,
        )
        val accepted =
            f.initial.copy(
                days =
                    f.initial.days.map { day ->
                        if (day.date == date)
                            day.copy(
                                entries =
                                    listOf(
                                        entry(
                                            command.id,
                                            WorkDay.Unassigned(date),
                                            AttendanceStatus.ACCEPTED,
                                        )
                                    )
                            )
                        else day
                    }
            )
        val reconciled = summarizeAttendanceHistory(accepted, listOf(queued), f.now)
        assertEquals(1, reconciled.presentDays)
        assertTrue(reconciled.days.last().commands.isEmpty())
    }

    @Test
    fun anOngoingShiftDoesNotBecomeAnIncompletePastDay() {
        // The previous work date can still own a shift in progress after company midnight.
        val date = f.base.directory.asOf.minus(1, DateTimeUnit.DAY)
        val ongoing =
            WorkDay.Work(
                date,
                shift,
                f.now - 1.hours,
                f.now + 7.hours,
                480,
                CalendarOrigin.PATTERN,
                0,
            )
        val snapshot =
            f.initial.copy(
                days =
                    listOf(
                        AttendanceDay(
                            date,
                            listOf(entry("in", ongoing, AttendanceStatus.ACCEPTED)),
                            0,
                            0,
                            true,
                            null,
                        )
                    )
            )
        assertEquals(0, summarizeAttendanceHistory(snapshot, emptyList(), f.now).reviewDays)
        assertEquals(
            1,
            summarizeAttendanceHistory(snapshot, emptyList(), f.now + 8.hours).reviewDays,
        )
    }

    private fun entry(id: String, day: WorkDay, status: AttendanceStatus) =
        AttendanceEntry(
            id,
            AttendanceKind.CLOCK_IN,
            f.now - 48.hours,
            f.now - 48.hours,
            false,
            status,
            emptySet(),
            day,
            0,
        )
}
