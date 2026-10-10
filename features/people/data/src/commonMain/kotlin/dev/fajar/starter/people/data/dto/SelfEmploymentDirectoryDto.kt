package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SelfEmploymentDirectoryDto(
    val asOf: String,
    val timezone: String,
    val employments: EmploymentPageDto,
)

@Serializable
data class EmploymentPageDto(val items: List<EmploymentDto>, val nextCursor: String? = null)
