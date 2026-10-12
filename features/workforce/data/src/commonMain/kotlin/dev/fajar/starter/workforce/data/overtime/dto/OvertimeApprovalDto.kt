package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeApprovalDto(
    val id: String,
    val status: String,
    val currentStep: Int,
    val stageCount: Int,
    val version: Long,
)
