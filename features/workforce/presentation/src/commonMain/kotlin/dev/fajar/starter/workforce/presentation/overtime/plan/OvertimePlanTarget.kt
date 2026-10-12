package dev.fajar.starter.workforce.presentation.overtime.plan

data class OvertimePlanTarget(
    val workspaceId: String,
    val employeeId: String,
    val workDate: String,
    val employmentAfter: String? = null,
)
