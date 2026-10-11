package dev.fajar.starter.sync.data.dto

import kotlinx.serialization.Serializable

@Serializable data class SyncResourceDto(val collection: String, val id: String, val version: Long)
