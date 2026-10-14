package dev.fajar.starter.people.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryRecordDto(
    val id: String,
    val version: Long,
    val employeeNumber: String,
    val name: String,
    val positionId: String? = null,
    val position: String? = null,
    val departmentId: String? = null,
    val department: String? = null,
) {
    override fun toString() = "DirectoryRecordDto([redacted])"
}
