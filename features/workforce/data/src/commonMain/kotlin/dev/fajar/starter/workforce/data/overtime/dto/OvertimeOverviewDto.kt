package dev.fajar.starter.workforce.data.overtime.dto

import dev.fajar.starter.workforce.data.dto.WorkforceDirectoryDto
import kotlinx.serialization.Serializable

@Serializable
data class OvertimeOverviewDto(
    val directory: WorkforceDirectoryDto,
    val employeeId: String?,
    val from: String,
    val until: String,
    val status: String?,
    val employmentAfter: String?,
    val requestAfter: String?,
    val requests: OvertimePageDto<OvertimeSummaryDto>,
)
