package dev.fajar.starter.people.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryEmployeeDto(
    val id: String,
    val employeeNumber: String,
    val name: String,
    val position: String? = null,
    val department: String? = null,
)
