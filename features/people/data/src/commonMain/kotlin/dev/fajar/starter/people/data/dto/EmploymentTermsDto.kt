package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmploymentTermsDto(
    val effectiveFrom: String,
    val contract: String,
    val startDate: String,
    val endDate: String? = null,
    val status: String,
    val branchId: String? = null,
    val departmentId: String? = null,
    val positionId: String? = null,
    val costCenterId: String? = null,
    val managerId: String? = null,
)
