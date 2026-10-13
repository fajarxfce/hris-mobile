package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeHistoryPageDto(
    val requestId: String,
    val requestVersion: Long,
    val after: Long,
    val history: OvertimePageDto<OvertimeChangeDto>,
)
