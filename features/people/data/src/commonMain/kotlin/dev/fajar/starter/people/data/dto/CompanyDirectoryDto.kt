package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CompanyDirectoryDto(
    val asOf: String,
    val timezone: String,
    val employees: DirectoryEmployeePageDto,
)
