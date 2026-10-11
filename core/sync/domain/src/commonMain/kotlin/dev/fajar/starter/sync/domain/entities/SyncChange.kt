package dev.fajar.starter.sync.domain.entities

data class SyncChange(val reference: SyncReference, val operation: SyncOperation)

enum class SyncOperation {
    UPSERT,
    DELETE,
}
