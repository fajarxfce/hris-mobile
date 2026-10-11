package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkforceEmploymentDto(
    val id: String,
    val employeeNumber: String,
    val status: String,
    val version: Long,
)
