package dev.fajar.starter.documents.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentCatalogReadAdmissionDto(
    val revision: String,
    val kind: String,
    val code: String?,
)
