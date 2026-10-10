package dev.fajar.starter.people.domain.entities

import kotlinx.datetime.LocalDate

data class EmploymentDetails(
    val asOf: LocalDate,
    val employee: Employment,
    val branch: EmploymentAssignment?,
    val department: EmploymentAssignment?,
    val position: EmploymentAssignment?,
    val costCenter: EmploymentAssignment?,
    val manager: EmploymentManager?,
)

data class EmploymentAssignment(
    val id: String,
    val code: String,
    val name: String,
    val active: Boolean,
)

data class EmploymentManager(
    val id: String,
    val employeeNumber: String,
    val legalName: String,
    val working: Boolean,
)
