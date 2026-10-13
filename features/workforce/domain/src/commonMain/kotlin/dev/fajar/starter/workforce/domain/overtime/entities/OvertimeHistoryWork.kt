package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeHistoryWork(
    val requestId: String,
    val employeeId: String,
    val requestVersion: Long,
    val acquisitionId: String,
    val after: Long,
)
