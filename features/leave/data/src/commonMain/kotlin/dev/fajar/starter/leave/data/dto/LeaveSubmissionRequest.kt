package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveSubmissionRequest(
    val id: String,
    val employeeId: String,
    val typeId: String,
    val days: List<LeaveRequestedDayDto>,
    val reason: String,
    val attachmentRevisionIds: List<String>,
    val previewRevision: String,
)
