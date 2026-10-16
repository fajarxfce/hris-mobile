package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveSubmissionCommandDto(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val requestId: String,
    val employeeId: String,
    val typeId: String,
    val days: List<LeaveRequestedDayDto>,
    val previewRevision: String,
    val typeName: String,
    val chargedHalfDays: Int,
    val reason: String,
    val attachmentRevisionIds: List<String>,
    val createdAt: String,
    val workingRevision: String? = null,
)
