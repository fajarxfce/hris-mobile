package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveRequestPolicyDto(
    val typeId: String,
    val code: String,
    val revision: Long,
    val name: String,
    val paid: Boolean,
    val allowPartialDays: Boolean,
    val attachmentRequired: Boolean,
)
