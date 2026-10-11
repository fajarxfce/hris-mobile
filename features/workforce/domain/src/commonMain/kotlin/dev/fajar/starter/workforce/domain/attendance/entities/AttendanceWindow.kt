package dev.fajar.starter.workforce.domain.attendance.entities

/** Recent evidence for capture and monthly history have independent cache ownership. */
enum class AttendanceWindow {
    RECENT,
    MONTHLY,
}
