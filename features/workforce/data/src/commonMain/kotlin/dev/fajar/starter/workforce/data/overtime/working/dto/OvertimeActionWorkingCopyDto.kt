package dev.fajar.starter.workforce.data.overtime.working.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeActionWorkingCopyDto(
    val requestId: String,
    val employeeId: String,
    val action: String,
    val revision: String,
    val savedAt: String,
    val requestVersion: Long,
    val startsAt: String?,
    val endsAt: String?,
    val breakMinutes: String?,
    val reason: String,
    val hasEdits: Boolean = true,
)
