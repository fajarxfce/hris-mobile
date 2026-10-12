package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimePlanRequest(
    val id: String,
    val employeeId: String,
    val expectedEmploymentVersion: Long,
    val workDate: String,
    val requested: OvertimeIntervalRequest,
    val reason: String,
    val planningRevision: String,
)
