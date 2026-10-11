package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkforcePageDto(val items: List<WorkforceEmploymentDto>, val nextCursor: String? = null)
