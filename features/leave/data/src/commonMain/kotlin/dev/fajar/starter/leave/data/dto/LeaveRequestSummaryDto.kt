package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveRequestSummaryDto(
    val id: String,
    val employeeId: String,
    val typeCode: String,
    val typeName: String,
    val from: String,
    val until: String,
    val chargedDays: String,
    val status: String,
    val submittedAt: String,
    val version: Long,
)
