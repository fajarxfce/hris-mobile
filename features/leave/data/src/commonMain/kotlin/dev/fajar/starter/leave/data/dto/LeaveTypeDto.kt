package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveTypeDto(
    val id: String,
    val code: String,
    val name: String,
    val effectiveFrom: String,
    val paid: Boolean,
    val allowPartialDays: Boolean,
    val attachmentRequired: Boolean,
    val active: Boolean,
    val version: Long,
    val appliedRevision: Long,
)
