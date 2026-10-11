package dev.fajar.starter.workforce.domain.entities

data class WorkforceEmployment(
    val id: String,
    val employeeNumber: String,
    val status: WorkforceEmploymentStatus,
    val version: Long,
)

enum class WorkforceEmploymentStatus {
    ACTIVE,
    PROBATION,
    SUSPENDED,
    ENDED,
}
