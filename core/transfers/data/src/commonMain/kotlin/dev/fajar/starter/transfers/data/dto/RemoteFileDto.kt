package dev.fajar.starter.transfers.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class RemoteFileDto(val id: String, val file: FileMetadataDto, val version: String)
