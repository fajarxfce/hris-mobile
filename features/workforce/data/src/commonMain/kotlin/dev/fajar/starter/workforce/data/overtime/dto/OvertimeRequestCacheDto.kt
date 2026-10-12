package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeRequestCacheDto(
    val requestId: String,
    val employeeId: String,
    val employmentAfter: String?,
    val historyAfter: Long?,
    val request: OvertimeRequestDto,
    val approval: OvertimeApprovalDto?,
    val history: OvertimePageDto<OvertimeChangeDto>,
    val actions: List<String>,
)
