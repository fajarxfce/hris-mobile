package dev.fajar.starter.workforce.presentation

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.WorkDay
import dev.fajar.starter.workforce.presentation.attendance.attendanceStamp
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

class AttendanceStampTest {
    private val date = LocalDate(2026, 10, 11)
    private val time = Instant.parse("2026-10-11T01:00:00Z")
    private val command =
        AttendanceCommand(
            "command",
            "account",
            "audience",
            "company",
            "employee",
            date,
            AttendanceKind.CLOCK_IN,
            time,
            "device",
            null,
            true,
            null,
        )
    private val entry =
        AttendanceEntry(
            "command",
            AttendanceKind.CLOCK_IN,
            time,
            time,
            true,
            AttendanceStatus.PENDING,
            emptySet(),
            WorkDay.Unassigned(date),
            0,
        )
    private val day = AttendanceDay(date, listOf(entry), 0, 1, true, null)

    @Test
    fun localSaveReceiptAndReviewRemainDifferentOutcomes() {
        val saved =
            attendanceStamp(AttendanceKind.CLOCK_IN, null, listOf(AttendanceCommandRecord(command)))
        assertEquals(time, saved?.capturedAt)
        assertEquals(AppString.AttendanceSaved, saved?.status)
        val received =
            attendanceStamp(
                AttendanceKind.CLOCK_IN,
                null,
                listOf(
                    AttendanceCommandRecord(
                        command,
                        outcome = AttendanceCommandOutcome.Confirmed(0),
                    )
                ),
            )
        assertEquals(AppString.AttendanceReceived, received?.status)
        assertEquals(
            AppString.AttendancePending,
            attendanceStamp(AttendanceKind.CLOCK_IN, day, emptyList())?.status,
        )
        val reviewed = day.copy(entries = listOf(entry.copy(status = AttendanceStatus.ACCEPTED)))
        assertEquals(
            AppString.AttendanceAccepted,
            attendanceStamp(AttendanceKind.CLOCK_IN, reviewed, emptyList())?.status,
        )
    }

    @Test
    fun responseLossRetainsTheOriginalTimeWithoutImplyingApproval() {
        val pending =
            AttendanceCommandRecord(command, outcome = AttendanceCommandOutcome.Unconfirmed())
        assertEquals(
            time,
            attendanceStamp(AttendanceKind.CLOCK_IN, null, listOf(pending))?.capturedAt,
        )
        assertEquals(
            AppString.AttendanceUnconfirmed,
            attendanceStamp(AttendanceKind.CLOCK_IN, null, listOf(pending))?.status,
        )
        assertNull(attendanceStamp(AttendanceKind.CLOCK_OUT, null, listOf(pending)))
    }

    @Test
    fun correctionReplacesOriginalTimesAndCanLeaveClockOutUnset() {
        val correctedTime = Instant.parse("2026-10-11T01:30:00Z")
        val corrected =
            day.copy(correction = AttendanceCorrection("correction", correctedTime, null, 1))
        val stamp =
            attendanceStamp(
                AttendanceKind.CLOCK_IN,
                corrected,
                listOf(AttendanceCommandRecord(command)),
            )
        assertEquals(correctedTime, stamp?.capturedAt)
        assertEquals(AppString.AttendanceCorrected, stamp?.status)
        assertNull(attendanceStamp(AttendanceKind.CLOCK_OUT, corrected, emptyList()))
    }

    @Test
    fun aRejectedAttemptDoesNotReplaceAnAcceptedEntry() {
        val reviewed =
            day.copy(
                entries =
                    listOf(
                        entry.copy(status = AttendanceStatus.ACCEPTED),
                        entry.copy(
                            id = "rejected",
                            status = AttendanceStatus.REJECTED,
                            capturedAt = Instant.parse("2026-10-11T02:00:00Z"),
                        ),
                    )
            )
        val stamp = attendanceStamp(AttendanceKind.CLOCK_IN, reviewed, emptyList())
        assertEquals(time, stamp?.capturedAt)
        assertEquals(AppString.AttendanceAccepted, stamp?.status)
    }
}
