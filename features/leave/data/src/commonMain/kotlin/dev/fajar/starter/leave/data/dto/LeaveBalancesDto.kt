package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveBalancesDto(
    val employee: LeaveEmployeeReferenceDto,
    val year: Int,
    val balances: LeavePageDto<LeaveBalanceSummaryDto>,
)
