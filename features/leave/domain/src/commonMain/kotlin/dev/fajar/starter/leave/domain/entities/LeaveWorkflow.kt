package dev.fajar.starter.leave.domain.entities

/** A read projection; assignment and decision eligibility stay authoritative on the server. */
data class LeaveWorkflow(
    val id: String,
    val status: LeaveWorkflowStatus,
    val currentStep: Int,
    val version: Long,
)
