package dev.fajar.starter.workforce.presentation.attendance.history.formatters

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceHistoryStatus

fun attendanceHistoryStatusLabel(status: AttendanceHistoryStatus): AppString =
    when (status) {
        AttendanceHistoryStatus.PRESENT -> AppString.AttendancePresent
        AttendanceHistoryStatus.ABSENT -> AppString.AttendanceAbsent
        AttendanceHistoryStatus.PENDING -> AppString.AttendancePending
        AttendanceHistoryStatus.SYNC_PENDING -> AppString.SyncPending
        AttendanceHistoryStatus.REJECTED -> AppString.AttendanceRejected
        AttendanceHistoryStatus.INCOMPLETE -> AppString.AttendanceIncomplete
        AttendanceHistoryStatus.DAY_OFF -> AppString.CalendarOff
        AttendanceHistoryStatus.NO_SCHEDULE -> AppString.CalendarUnassigned
        AttendanceHistoryStatus.NOT_RECORDED -> AppString.AttendanceNotRecorded
    }
