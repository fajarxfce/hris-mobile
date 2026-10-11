package dev.fajar.starter.workforce.domain.attendance.entities

import dev.fajar.starter.common.result.Failure

sealed interface AttendanceCommandOutcome {
    data object Pending : AttendanceCommandOutcome

    data class Unconfirmed(val failure: Failure? = null) : AttendanceCommandOutcome

    data class Rejected(val failure: Failure) : AttendanceCommandOutcome

    data class Confirmed(val version: Long) : AttendanceCommandOutcome
}

data class AttendanceCommandRecord(
    val command: AttendanceCommand,
    val revision: Long = 0,
    val attempts: Int = 0,
    val outcome: AttendanceCommandOutcome = AttendanceCommandOutcome.Pending,
)

data class AttendanceCommandPage(val items: List<AttendanceCommandRecord>, val nextCursor: String?)

enum class AttendanceCommandAdmission {
    STORED,
    OCCUPIED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}

data class AttendanceView(
    val snapshot: AttendanceSnapshot?,
    val commands: List<AttendanceCommandRecord>,
)
