package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmploymentAssignmentDto(
    val id: String,
    val code: String,
    val name: String,
    val active: Boolean,
)

@Serializable
data class EmploymentManagerDto(
    val id: String,
    val employeeNumber: String,
    val legalName: String,
    val working: Boolean,
)
