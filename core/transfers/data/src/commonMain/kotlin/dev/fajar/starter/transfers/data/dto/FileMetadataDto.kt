package dev.fajar.starter.transfers.data.dto

import kotlinx.serialization.Serializable

@Serializable data class FileMetadataDto(val name: String, val mediaType: String, val size: Long)
