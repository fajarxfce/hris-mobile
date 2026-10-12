package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable data class DocumentMutationDto(val id: String, val version: Long)
