package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.resolveOvertimeLocalInterval
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.*

class OvertimeLocalTimePolicyTest {
    @Test
    fun overnightInputUsesTheCompanyZoneIncludingFractionalOffsets() {
        val input =
            OvertimeLocalIntervalInput(
                LocalDateTime(2026, 10, 12, 23, 30),
                LocalDateTime(2026, 10, 13, 1, 30),
                15,
            )
        for ((zone, expected) in
            listOf(
                "Asia/Jakarta" to "2026-10-12T16:30:00Z",
                "Asia/Kathmandu" to "2026-10-12T17:45:00Z",
                "Pacific/Kiritimati" to "2026-10-12T09:30:00Z",
            )) {
            val result =
                assertIs<AppResult.Success<OvertimeIntervalInput>>(
                        resolveOvertimeLocalInterval(input, zone)
                    )
                    .value
            assertEquals(Instant.parse(expected), result.startsAt)
            assertEquals(input.startsAt, result.startsAt.toLocalDateTime(TimeZone.of(zone)))
            assertEquals(input.endsAt, result.endsAt.toLocalDateTime(TimeZone.of(zone)))
            assertEquals(15, result.breakMinutes)
        }
    }

    @Test
    fun clockGapsAreRejectedInsteadOfShiftingEnteredTime() {
        for ((zone, start) in
            listOf(
                "America/New_York" to LocalDateTime(2026, 3, 8, 2, 30),
                "Australia/Lord_Howe" to LocalDateTime(2026, 10, 4, 2, 15),
            )) {
            val input =
                OvertimeLocalIntervalInput(start, LocalDateTime(start.date, LocalTime(4, 0)), 0)
            assertEquals(
                "client.overtime_time_gap",
                assertIs<AppResult.Failed>(resolveOvertimeLocalInterval(input, zone)).failure.code,
            )
        }
    }

    @Test
    fun repeatedHoursRequireAnUnchangedKnownInstant() {
        val input =
            OvertimeLocalIntervalInput(
                LocalDateTime(2026, 11, 1, 1, 30),
                LocalDateTime(2026, 11, 1, 2, 30),
                0,
            )
        assertEquals(
            "client.overtime_time_ambiguous",
            assertIs<AppResult.Failed>(resolveOvertimeLocalInterval(input, "America/New_York"))
                .failure
                .code,
        )
        for (start in listOf("2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z")) {
            val original =
                OvertimeInterval(
                    Instant.parse(start),
                    Instant.parse("2026-11-01T07:30:00Z"),
                    0,
                    if (start.contains("05:30")) 120 else 60,
                )
            val result =
                assertIs<AppResult.Success<OvertimeIntervalInput>>(
                        resolveOvertimeLocalInterval(input, "America/New_York", original)
                    )
                    .value
            assertEquals(original.startsAt, result.startsAt)
            assertEquals(original.endsAt, result.endsAt)
        }
        val halfHour =
            input.copy(
                startsAt = LocalDateTime(2026, 4, 5, 1, 45),
                endsAt = LocalDateTime(2026, 4, 5, 3, 0),
            )
        assertEquals(
            "client.overtime_time_ambiguous",
            assertIs<AppResult.Failed>(
                    resolveOvertimeLocalInterval(halfHour, "Australia/Lord_Howe")
                )
                .failure
                .code,
        )
    }

    @Test
    fun invalidPrecisionRangeOrZoneCannotBecomeACommandInterval() {
        val valid =
            OvertimeLocalIntervalInput(
                LocalDateTime(2026, 10, 12, 18, 0),
                LocalDateTime(2026, 10, 12, 20, 0),
                0,
            )
        assertIs<AppResult.Failed>(resolveOvertimeLocalInterval(valid, "invalid/zone"))
        assertIs<AppResult.Failed>(
            resolveOvertimeLocalInterval(
                valid.copy(startsAt = LocalDateTime(1999, 10, 12, 18, 0)),
                "UTC",
            )
        )
        assertIs<AppResult.Failed>(
            resolveOvertimeLocalInterval(
                valid.copy(endsAt = LocalDateTime(2026, 10, 12, 20, 0, 1)),
                "UTC",
            )
        )
    }
}
