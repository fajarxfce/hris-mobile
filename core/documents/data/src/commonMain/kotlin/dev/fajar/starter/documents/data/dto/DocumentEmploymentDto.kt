package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentEmploymentDto(
    val id: String,
    val employeeNumber: String,
    val status: String,
    val version: Long,
)
