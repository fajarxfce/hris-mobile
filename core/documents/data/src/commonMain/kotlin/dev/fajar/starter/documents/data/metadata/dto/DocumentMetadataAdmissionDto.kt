package dev.fajar.starter.documents.data.metadata.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentMetadataAdmissionDto(val revision: String, val kind: String, val code: String?)
