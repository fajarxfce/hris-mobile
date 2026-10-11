package dev.fajar.starter.workforce.domain.attendance.entities

import dev.fajar.starter.location.domain.entities.LocationFix
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Immutable evidence and operation ID survive response loss and process recreation. */
data class AttendanceCommand(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val employeeId: String,
    val workDate: LocalDate,
    val kind: AttendanceKind,
    val capturedAt: Instant,
    val deviceId: String,
    val windowId: String?,
    val offline: Boolean,
    val location: LocationFix?,
) {
    val resourceId: String
        get() = "$employeeId/$workDate/${kind.name}"

    fun belongsTo(access: WorkforceAccess) =
        accountId == access.accountId &&
            audience == access.audience &&
            companyId == access.companyId

    override fun toString() = "AttendanceCommand(redacted)"
}

data class AttendanceCaptureWindow(
    val id: String,
    val employeeId: String,
    val deviceId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

data class AttendanceReceipt(val id: String, val version: Long)
