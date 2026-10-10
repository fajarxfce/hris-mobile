package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmploymentDetailsDto(
    val asOf: String,
    val employee: EmploymentDto,
    val branch: EmploymentAssignmentDto? = null,
    val department: EmploymentAssignmentDto? = null,
    val position: EmploymentAssignmentDto? = null,
    val costCenter: EmploymentAssignmentDto? = null,
    val manager: EmploymentManagerDto? = null,
)
