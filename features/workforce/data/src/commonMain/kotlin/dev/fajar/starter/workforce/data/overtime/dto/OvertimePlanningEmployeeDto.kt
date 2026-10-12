package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimePlanningEmployeeDto(
    val id: String,
    val employeeNumber: String,
    val legalName: String,
    val status: String,
    val version: Long,
)
