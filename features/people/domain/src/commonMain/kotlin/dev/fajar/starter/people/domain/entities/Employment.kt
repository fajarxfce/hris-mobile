package dev.fajar.starter.people.domain.entities

import kotlinx.datetime.LocalDate

data class Employment(
    val id: String,
    val companyId: String,
    val personId: String,
    val accountId: String?,
    val employeeNumber: String,
    val legalName: String,
    val email: String?,
    val contract: EmploymentContract,
    val status: EmploymentStatus,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val version: Long,
    val appliedRevision: Long,
)

enum class EmploymentContract {
    Permanent,
    FixedTerm,
}

enum class EmploymentStatus {
    Probation,
    Active,
    Suspended,
    Ended,
}
