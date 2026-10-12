package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeWorkflowDto(
    val id: String,
    val status: String,
    val currentStep: Int,
    val stages: List<List<String>>,
    val version: Long,
)
