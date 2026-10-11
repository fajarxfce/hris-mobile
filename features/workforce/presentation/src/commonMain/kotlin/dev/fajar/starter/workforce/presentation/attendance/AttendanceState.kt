package dev.fajar.starter.workforce.presentation.attendance

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.location.domain.entities.LocationSettings
import dev.fajar.starter.workforce.domain.attendance.entities.*

data class AttendanceState(
    val selection: AttendanceSelection = AttendanceSelection(),
    val content: AttendanceContent? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val working: Boolean = false,
    val failure: Failure? = null,
    val needsVerification: Boolean = false,
    val settingsTarget: LocationSettings? = null,
    val withoutLocationKind: AttendanceKind? = null,
    val dialog: AttendanceDialog? = null,
)

enum class AttendanceDialog {
    QUEUE,
    EMPLOYMENT,
}
