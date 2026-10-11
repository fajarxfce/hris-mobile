package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveWorkflowDto(
    val id: String,
    val status: String,
    val currentStep: Int,
    val version: Long,
)
