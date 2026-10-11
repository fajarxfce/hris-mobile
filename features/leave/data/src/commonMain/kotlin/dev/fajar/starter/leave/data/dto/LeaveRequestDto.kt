package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveRequestDto(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val ownerAccountId: String? = null,
    val submittedAt: String,
    val policy: LeaveRequestPolicyDto,
    val days: List<LeaveDayDto>,
    val chargedDays: String,
    val reason: String,
    val status: String,
    val version: Long,
    val approval: LeaveWorkflowDto,
    val cancellation: LeaveWorkflowDto? = null,
    val history: LeavePageDto<LeaveChangeDto>,
    val availableActions: Set<String>,
    val attachments: List<LeaveAttachmentDto>,
)
