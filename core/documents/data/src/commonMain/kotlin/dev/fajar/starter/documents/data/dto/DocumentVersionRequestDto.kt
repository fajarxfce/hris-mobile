package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable data class DocumentVersionRequestDto(val expectedVersion: Long, val reason: String)
