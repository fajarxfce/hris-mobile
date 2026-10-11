package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryDepartmentsDto(val items: List<DirectoryDepartmentDto>, val hasMore: Boolean)
