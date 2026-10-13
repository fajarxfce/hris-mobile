package dev.fajar.starter.workforce.domain.attendance.entities

import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Instant

/** A bounded self-employment snapshot; capture still requires current application admission. */
data class AttendanceSnapshot(
    val access: WorkforceAccess,
    val generation: String,
    val directory: WorkforceDirectory,
    val employeeId: String?,
    val period: CalendarPeriod,
    val calendar: WorkCalendar?,
    val days: List<AttendanceDay>,
    val fetchedAt: Instant,
    val refreshAfter: Instant,
    val after: String? = null,
) {
    override fun toString() = "AttendanceSnapshot(redacted)"
}
