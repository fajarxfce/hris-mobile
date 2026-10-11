package dev.fajar.starter.sync.domain.entities

data class SyncReference(val collection: SyncCollection, val id: String, val version: Long)
