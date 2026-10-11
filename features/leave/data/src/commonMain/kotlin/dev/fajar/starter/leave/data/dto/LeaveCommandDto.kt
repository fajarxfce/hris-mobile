package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveCommandDto(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val requestId: String,
    val expectedVersion: Long,
    val action: String,
    val reason: String,
    val createdAt: String,
)
