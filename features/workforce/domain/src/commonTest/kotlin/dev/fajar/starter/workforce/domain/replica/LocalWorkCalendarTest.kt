package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.calendarPeriod
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.policies.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.*

class LocalWorkCalendarTest {
    private val f = WorkforceSyncFixture()
    private val monday = LocalDate(2026, 10, 12)

    @Test
    fun rosterOverridesHolidayAndPatternWhileAnExplicitOffRemainsOff() {
        val holiday = f.holiday.copy(workDate = monday)
        val roster = f.roster.copy(shift = null)
        val day =
            resolve(
                monday,
                schedules = listOf(f.schedule),
                roster = listOf(roster),
                holidays = listOf(holiday),
            )
        assertEquals(WorkDay.Off(monday, CalendarOrigin.ROSTER, roster.version, null), day)
        assertEquals(
            WorkDay.Off(monday, CalendarOrigin.HOLIDAY, holiday.version, holiday.id),
            resolve(monday, schedules = listOf(f.schedule), holidays = listOf(holiday)),
        )
        assertEquals(
            CalendarOrigin.PATTERN,
            (resolve(
                    monday,
                    schedules = listOf(f.schedule),
                    holidays = listOf(holiday.copy(active = false)),
                )
                    as WorkDay.Work)
                .origin,
        )
    }

    @Test
    fun latestEffectiveRuleWinsAndItsMissingWeekdayIsDifferentFromUnassigned() {
        val later =
            f.schedule.copy(
                id = workforceId(22),
                version = 9,
                effectiveFrom = monday,
                days = emptyMap(),
            )
        val future =
            later.copy(
                id = workforceId(23),
                version = 10,
                effectiveFrom = monday.plus(7, DateTimeUnit.DAY),
                days = f.schedule.days,
            )
        assertEquals(
            WorkDay.Off(monday, CalendarOrigin.PATTERN, 9, null),
            resolve(monday, schedules = listOf(f.schedule, future, later)),
        )
        assertEquals(
            WorkDay.Unassigned(LocalDate(2019, 12, 31)),
            resolve(LocalDate(2019, 12, 31), schedules = listOf(f.schedule)),
        )
    }

    @Test
    fun overnightShiftKeepsItsWorkDateAndUsesElapsedTimeMinusBreak() {
        val day = resolve(monday, roster = listOf(f.roster)) as WorkDay.Work
        assertEquals(monday, day.date)
        assertEquals(Instant.parse("2026-10-12T15:00:00Z"), day.startsAt)
        assertEquals(Instant.parse("2026-10-12T23:00:00Z"), day.endsAt)
        assertEquals(420, day.plannedMinutes)
    }

    @Test
    fun daylightSavingGapsAreRejectedAndOverlapUsesTheEarlierOffset() {
        val shift =
            f.shift.copy(
                timezone = "America/New_York",
                startsAt = LocalTime(2, 30),
                endsAt = LocalTime(10, 0),
                breakMinutes = 0,
            )
        val gap = localScheduledDay(LocalDate(2026, 3, 8), shift, CalendarOrigin.PATTERN, 0)
        assertEquals("shift_timezone_gap", assertIs<AppResult.Failed>(gap).failure.code)
        val overlap =
            assertIs<AppResult.Success<WorkDay.Work>>(
                    localScheduledDay(
                        LocalDate(2026, 11, 1),
                        shift.copy(startsAt = LocalTime(1, 30), endsAt = LocalTime(3, 30)),
                        CalendarOrigin.PATTERN,
                        0,
                    )
                )
                .value
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), overlap.startsAt)
        assertEquals(180, overlap.plannedMinutes)
    }

    @Test
    fun queriesStayBoundedButHistoryIsNotRestrictedToAYear() {
        assertIs<AppResult.Success<*>>(calendarPeriod(monday, LocalDate(1990, 1, 1)))
        assertIs<AppResult.Success<*>>(calendarPeriod(monday, LocalDate(2040, 1, 2)))
        assertIs<AppResult.Failed>(calendarPeriod(monday, LocalDate(2026, 10, 13)))
        val empty = WorkforceReplicaFacts(null, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(
            31,
            (resolveLocalWorkCalendar(
                    CalendarPeriod(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)),
                    empty,
                )
                    as AppResult.Success)
                .value
                .days
                .size,
        )
        assertIs<AppResult.Failed>(
            resolveLocalWorkCalendar(
                CalendarPeriod(LocalDate(2026, 10, 1), LocalDate(2026, 11, 1)),
                empty,
            )
        )
        assertIs<AppResult.Failed>(
            resolveLocalWorkCalendar(
                CalendarPeriod(monday.plus(1, DateTimeUnit.DAY), monday),
                empty,
            )
        )
    }

    private fun resolve(
        date: LocalDate,
        schedules: List<WorkforceRecord.Schedule> = emptyList(),
        roster: List<WorkforceRecord.Roster> = emptyList(),
        holidays: List<WorkforceRecord.Holiday> = emptyList(),
    ): WorkDay =
        assertIs<AppResult.Success<WorkCalendar>>(
                resolveLocalWorkCalendar(
                    CalendarPeriod(date, date),
                    WorkforceReplicaFacts(null, emptyList(), schedules, roster, holidays),
                )
            )
            .value
            .days
            .single()
}
