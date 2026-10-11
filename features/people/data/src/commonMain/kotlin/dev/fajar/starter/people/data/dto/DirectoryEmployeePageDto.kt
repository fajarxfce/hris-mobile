package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryEmployeePageDto(
    val items: List<DirectoryEmployeeDto>,
    val nextCursor: String? = null,
)
