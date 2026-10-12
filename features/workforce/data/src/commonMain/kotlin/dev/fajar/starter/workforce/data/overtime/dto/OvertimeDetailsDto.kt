package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeDetailsDto(
    val request: OvertimeRequestDto,
    val approval: OvertimeWorkflowDto?,
    val history: OvertimePageDto<OvertimeChangeDto>,
    val actions: List<String>,
)
