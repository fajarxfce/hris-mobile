package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveCommandOutcomeDto(
    val phase: String,
    val attempts: Int,
    val receiptVersion: Long? = null,
    val failure: LeaveCommandFailureDto? = null,
)
