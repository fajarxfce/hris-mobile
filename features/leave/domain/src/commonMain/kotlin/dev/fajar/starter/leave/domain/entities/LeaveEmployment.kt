package dev.fajar.starter.leave.domain.entities

data class LeaveEmployment(
    val id: String,
    val employeeNumber: String,
    val status: LeaveEmploymentStatus,
    val version: Long,
)

enum class LeaveEmploymentStatus {
    ACTIVE,
    PROBATION,
    SUSPENDED,
    ENDED,
}
