package dev.fajar.starter.workforce.domain.attendance.entities

import dev.fajar.starter.workforce.domain.entities.WorkDay
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

enum class AttendanceKind {
    CLOCK_IN,
    CLOCK_OUT,
}

enum class AttendanceStatus {
    ACCEPTED,
    PENDING,
    REJECTED,
}

data class AttendanceEntry(
    val id: String,
    val kind: AttendanceKind,
    val capturedAt: Instant,
    val receivedAt: Instant,
    val offline: Boolean,
    val status: AttendanceStatus,
    val issues: Set<String>,
    val schedule: WorkDay,
    val version: Long,
)

data class AttendanceDay(
    val date: LocalDate,
    val entries: List<AttendanceEntry>,
    val acceptedMinutes: Long,
    val pendingCount: Int,
    val incomplete: Boolean,
    val correction: AttendanceCorrection?,
)

data class AttendanceCorrection(
    val id: String,
    val clockIn: Instant?,
    val clockOut: Instant?,
    val version: Long,
)
