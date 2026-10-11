package dev.fajar.starter.leave.domain.entities

enum class LeaveWorkflowStatus {
    PENDING,
    BLOCKED,
    APPROVED,
    REJECTED,
    CANCELLED,
}
