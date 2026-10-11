package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveBalanceSummaryDto(
    val typeId: String,
    val typeCode: String,
    val typeName: String,
    val balance: LeaveBalanceDto,
)
