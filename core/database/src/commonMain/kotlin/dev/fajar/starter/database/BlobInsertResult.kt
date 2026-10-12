package dev.fajar.starter.database

enum class BlobInsertResult {
    Stored,
    AlreadyStored,
    ScopeChanged,
    CapacityExceeded,
}
