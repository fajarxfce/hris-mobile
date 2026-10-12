package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentDirectoryDto(
    val accountId: String,
    val companyId: String,
    val asOf: String,
    val timezone: String,
    val employments: DocumentEmploymentPageDto,
)
