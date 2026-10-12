package dev.fajar.starter.documents.data.dto

/** Raw HEAD response fields, including duplicates for validation at the repository boundary. */
data class DocumentContentDto(
    val entityTags: List<String>,
    val lengths: List<String>,
    val mediaTypes: List<String>,
    val encodings: List<String>,
)
