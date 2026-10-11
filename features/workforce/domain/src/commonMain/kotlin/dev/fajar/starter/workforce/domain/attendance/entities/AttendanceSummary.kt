package dev.fajar.starter.workforce.domain.attendance.entities

data class AttendanceSummary(
    val presentDays: Int,
    val absentDays: Int,
    val reviewDays: Int,
    val acceptedMinutes: Long,
    val days: List<AttendanceHistoryDay>,
)

data class AttendanceHistoryDay(
    val attendance: AttendanceDay,
    val status: AttendanceHistoryStatus,
    val commands: List<AttendanceCommandRecord>,
)

enum class AttendanceHistoryStatus {
    PRESENT,
    ABSENT,
    PENDING,
    SYNC_PENDING,
    REJECTED,
    INCOMPLETE,
    DAY_OFF,
    NO_SCHEDULE,
    NOT_RECORDED,
}
