package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveAccountDto(
    val id: String,
    val employeeId: String,
    val typeId: String,
    val year: Int,
    val availableDays: String,
    val reservedDays: String,
    val consumedDays: String,
    val version: Long,
    val closed: Boolean,
    val typeCode: String,
    val typeName: String,
    val carry: LeaveCarryBalanceDto? = null,
)
