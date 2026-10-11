package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveEmploymentDto(
    val id: String,
    val employeeNumber: String,
    val status: String,
    val version: Long,
)
