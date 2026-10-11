package dev.fajar.starter.workforce.presentation.attendance.formatters

import androidx.compose.runtime.Composable
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun attendanceKind(kind: AttendanceKind) =
    appString(if (kind == AttendanceKind.CLOCK_IN) AppString.ClockIn else AppString.ClockOut)

@Composable
fun attendanceStatus(status: AttendanceStatus) =
    appString(
        when (status) {
            AttendanceStatus.ACCEPTED -> AppString.AttendanceAccepted
            AttendanceStatus.PENDING -> AppString.AttendancePending
            AttendanceStatus.REJECTED -> AppString.AttendanceRejected
        }
    )

@Composable
fun attendanceOutcome(outcome: AttendanceCommandOutcome) =
    appString(
        when (outcome) {
            AttendanceCommandOutcome.Pending -> AppString.AttendanceSaved
            is AttendanceCommandOutcome.Unconfirmed -> AppString.AttendanceUnconfirmed
            is AttendanceCommandOutcome.Rejected -> AppString.AttendanceRejected
            is AttendanceCommandOutcome.Confirmed -> AppString.AttendanceReceived
        }
    )

fun attendanceTime(instant: Instant, timezone: String): String {
    val time = instant.toLocalDateTime(TimeZone.of(timezone)).time
    return time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')
}
