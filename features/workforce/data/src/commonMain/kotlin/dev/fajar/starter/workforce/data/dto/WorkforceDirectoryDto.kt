package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkforceDirectoryDto(
    val accountId: String,
    val companyId: String,
    val asOf: String,
    val timezone: String,
    val employments: WorkforcePageDto,
)
