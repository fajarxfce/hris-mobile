package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmploymentDto(
    val id: String,
    val companyId: String,
    val employeeNumber: String,
    val person: PersonSummaryDto,
    val terms: EmploymentTermsDto,
    val version: Long,
    val appliedRevision: Long,
)
