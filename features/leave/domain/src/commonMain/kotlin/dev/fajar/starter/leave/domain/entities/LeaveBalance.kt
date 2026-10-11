package dev.fajar.starter.leave.domain.entities

data class LeaveBalance(
    val accountId: String,
    val typeId: String,
    val typeCode: String,
    val typeName: String,
    val year: Int,
    val available: LeaveDays,
    val reserved: LeaveDays,
    val consumed: LeaveDays,
    val closed: Boolean,
    val version: Long,
)
