package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentFileProofDto(val version: Int = 1, val size: Long, val sha256: String)
