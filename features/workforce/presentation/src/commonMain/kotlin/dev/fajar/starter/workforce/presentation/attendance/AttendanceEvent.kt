package dev.fajar.starter.workforce.presentation.attendance

import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceKind

sealed interface AttendanceEvent {
    data object Entered : AttendanceEvent

    data object Stopped : AttendanceEvent

    data object RefreshRequested : AttendanceEvent

    data object BackRequested : AttendanceEvent

    data object VerificationRequested : AttendanceEvent

    data object SettingsRequested : AttendanceEvent

    data class CaptureRequested(val kind: AttendanceKind, val withoutLocation: Boolean = false) :
        AttendanceEvent

    data class RetryRequested(val id: String) : AttendanceEvent

    data class RejectionDismissed(val id: String, val revision: Long) : AttendanceEvent

    data class EmploymentSelected(val id: String) : AttendanceEvent

    data class EmploymentPageRequested(val after: String?) : AttendanceEvent
}
