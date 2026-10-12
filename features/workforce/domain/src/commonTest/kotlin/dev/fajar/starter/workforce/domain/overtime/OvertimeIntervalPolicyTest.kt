package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.*

class OvertimeIntervalPolicyTest {
    private val start = Instant.parse("2026-10-12T10:00:00Z")
    private val input = OvertimeIntervalInput(start, start + 2.hours, 15)

    @Test
    fun durationUsesElapsedWholeMinutesAndExcludesTheDeclaredBreak() {
        val result =
            assertIs<AppResult.Success<OvertimeInterval>>(calculateOvertimeInterval(input)).value
        assertEquals(105, result.workedMinutes)
        assertEquals(15, result.breakMinutes)
        for (invalid in
            listOf(
                input.copy(startsAt = start + 1.seconds),
                input.copy(endsAt = start),
                input.copy(endsAt = start + 721.minutes),
                input.copy(breakMinutes = -1),
                input.copy(breakMinutes = 120),
                input.copy(endsAt = start + 3.hours, breakMinutes = 121),
            )) assertEquals(
            "invalid_overtime_interval",
            assertIs<AppResult.Failed>(calculateOvertimeInterval(invalid)).failure.code,
        )
    }

    @Test
    fun workDatesUseTheServerCompanyDateAndInclusivePlanningLimits() {
        val date = LocalDate(2026, 10, 12)
        for (offset in listOf(-31, 0, 90)) assertIs<AppResult.Success<*>>(
            validateOvertimeWorkDate(date.plus(offset, DateTimeUnit.DAY), date)
        )
        for (offset in listOf(-32, 91)) assertEquals(
            "overtime_date_out_of_range",
            assertIs<AppResult.Failed>(
                    validateOvertimeWorkDate(date.plus(offset, DateTimeUnit.DAY), date)
                )
                .failure
                .code,
        )
    }

    @Test
    fun overnightWorkAllowsTheFollowingMorningButExcludesRegularShiftTime() {
        val f = OvertimePlanningFixture()
        val date = f.review.workDate
        val shift =
            CalendarShift(
                "44444444-4444-4444-4444-444444444444",
                0,
                "NIGHT",
                "Night shift",
                LocalTime(22, 0),
                LocalTime(6, 0),
                60,
                "Asia/Jakarta",
                CalendarWorkMode.ONSITE,
            )
        val schedule =
            WorkDay.Work(
                date,
                shift,
                Instant.parse("2026-10-12T15:00:00Z"),
                Instant.parse("2026-10-12T23:00:00Z"),
                420,
                CalendarOrigin.PATTERN,
                0,
            )
        val review = f.review.copy(schedule = schedule)
        val afterShift =
            OvertimeIntervalInput(schedule.endsAt + 30.minutes, schedule.endsAt + 150.minutes, 0)
        assertEquals(
            120,
            assertIs<AppResult.Success<OvertimeInterval>>(
                    validateOvertimeRequestedInterval(review, afterShift)
                )
                .value
                .workedMinutes,
        )
        assertEquals(
            "overtime_overlaps_shift",
            assertIs<AppResult.Failed>(
                    validateOvertimeRequestedInterval(
                        review,
                        afterShift.copy(startsAt = schedule.endsAt - 1.minutes),
                    )
                )
                .failure
                .code,
        )
        assertEquals(
            "overtime_work_date_mismatch",
            assertIs<AppResult.Failed>(
                    validateOvertimeRequestedInterval(
                        review,
                        afterShift.copy(
                            startsAt = Instant.parse("2026-10-13T17:00:00Z"),
                            endsAt = Instant.parse("2026-10-13T18:00:00Z"),
                        ),
                    )
                )
                .failure
                .code,
        )
    }

    @Test
    fun daysOffUseLocalDayBoundariesIncludingDaylightSavingChanges() {
        val f = OvertimePlanningFixture()
        val date = LocalDate(2026, 11, 1)
        val review =
            f.review.copy(
                workDate = date,
                companyDate = date,
                timezone = "America/New_York",
                schedule = WorkDay.Off(date, CalendarOrigin.ROSTER, 0, null),
            )
        val lastHalfHour =
            OvertimeIntervalInput(
                Instant.parse("2026-11-02T04:30:00Z"),
                Instant.parse("2026-11-02T05:00:00Z"),
                0,
            )
        assertEquals(
            30,
            assertIs<AppResult.Success<OvertimeInterval>>(
                    validateOvertimeRequestedInterval(review, lastHalfHour)
                )
                .value
                .workedMinutes,
        )
        assertEquals(
            "overtime_work_date_mismatch",
            assertIs<AppResult.Failed>(
                    validateOvertimeRequestedInterval(
                        review,
                        lastHalfHour.copy(
                            startsAt = lastHalfHour.endsAt,
                            endsAt = lastHalfHour.endsAt + 30.minutes,
                        ),
                    )
                )
                .failure
                .code,
        )
        assertEquals(
            "overtime_schedule_missing",
            assertIs<AppResult.Failed>(
                    validateOvertimeRequestedInterval(
                        review.copy(schedule = WorkDay.Unassigned(date)),
                        lastHalfHour,
                    )
                )
                .failure
                .code,
        )
    }

    @Test
    fun actualTimeCannotExceedTheRequestedWindowOrWorkedDuration() {
        val request =
            OvertimeFixture()
                .request
                .copy(requested = OvertimeInterval(start, start + 2.hours, 30, 90))
        val actual = input.copy(breakMinutes = 30)
        assertEquals(
            90,
            assertIs<AppResult.Success<OvertimeInterval>>(
                    validateOvertimeActualInterval(request, actual, actual.endsAt)
                )
                .value
                .workedMinutes,
        )
        for (invalid in
            listOf(
                actual.copy(startsAt = start - 1.minutes),
                actual.copy(endsAt = actual.endsAt + 1.minutes),
                actual.copy(breakMinutes = 29),
            )) assertEquals(
            "overtime_outside_requested_window",
            assertIs<AppResult.Failed>(
                    validateOvertimeActualInterval(request, invalid, actual.endsAt + 1.hours)
                )
                .failure
                .code,
        )
        assertEquals(
            "overtime_not_ended",
            assertIs<AppResult.Failed>(
                    validateOvertimeActualInterval(request, actual, actual.endsAt - 1.seconds)
                )
                .failure
                .code,
        )
    }
}
