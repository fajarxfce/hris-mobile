package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveOverviewDto(
    val directory: LeaveDirectoryDto,
    val employeeId: String?,
    val year: Int,
    val employmentAfter: String?,
    val balanceAfter: String?,
    val status: String?,
    val requestAfter: String?,
    val balances: LeaveBalancesDto?,
    val requests: LeavePageDto<LeaveRequestSummaryDto>,
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val fetchedAt: String,
    val expiresAt: String,
)
