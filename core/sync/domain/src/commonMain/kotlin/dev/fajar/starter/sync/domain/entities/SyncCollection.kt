package dev.fajar.starter.sync.domain.entities

/** Explicit selection prevents server additions from changing an older client's projection. */
enum class SyncCollection {
    EXPENSE_CLAIMS,
    LEAVE_REQUESTS,
    LEAVE_BALANCES,
    OVERTIME_REQUESTS,
    PAYSLIPS,
    PAYROLL_PAYMENTS,
    INBOX,
    ATTENDANCE_DAYS,
    WORK_SCHEDULES,
    ROSTER_DAYS,
    WORK_HOLIDAYS,
    EXPENSE_PAYMENTS,
    DIRECTORY_EMPLOYEES,
    DOCUMENT_EVIDENCE,
    SELF_PROFILES,
}
