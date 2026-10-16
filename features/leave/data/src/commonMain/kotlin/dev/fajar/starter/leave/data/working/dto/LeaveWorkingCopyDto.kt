package dev.fajar.starter.leave.data.working.dto

import dev.fajar.starter.leave.data.dto.LeaveAttachmentDto
import dev.fajar.starter.leave.data.dto.LeaveTypeDto
import kotlinx.serialization.Serializable

@Serializable
data class LeaveWorkingCopyDto(
    val employeeId: String,
    val from: String,
    val until: String,
    val type: LeaveTypeDto?,
    val portion: String,
    val reason: String,
    val attachments: List<LeaveAttachmentDto>,
    val revision: String,
    val savedAt: String,
    val format: Int = 1,
)
