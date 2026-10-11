package dev.fajar.starter.leave.domain.entities

enum class LeaveCommandAdmission {
    STORED,
    OCCUPIED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}
