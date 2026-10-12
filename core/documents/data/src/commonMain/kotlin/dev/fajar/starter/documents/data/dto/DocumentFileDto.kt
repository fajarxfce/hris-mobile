package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentFileDto(
    val version: Int = 1,
    val audience: String,
    val accountId: String,
    val companyId: String,
    val id: String,
    val name: String,
    val mediaType: String,
    val size: Long,
    val createdAt: String,
    val download: DocumentDownloadSourceDto? = null,
) {
    override fun toString() = "DocumentFileDto([redacted])"
}
