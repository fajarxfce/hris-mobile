package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeCommandOutcomeDto(
    val phase: String,
    val attempts: Int,
    val receiptVersion: Long? = null,
    val failure: OvertimeCommandFailureDto? = null,
)
