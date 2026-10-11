package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** Submitted facts and current workflow metadata; history may be an earlier server page. */
data class LeaveRequestDetails(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val ownerAccountId: String?,
    val submittedAt: Instant,
    val policy: LeaveRequestPolicy,
    val days: List<LeaveDay>,
    val charged: LeaveDays,
    val reason: String,
    val status: LeaveStatus,
    val version: Long,
    val approval: LeaveWorkflow,
    val cancellation: LeaveWorkflow?,
    val history: LeavePage<LeaveChange>,
    val availableActions: Set<LeaveAction>,
    val attachments: List<LeaveAttachment>,
)
