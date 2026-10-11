package dev.fajar.starter.database

enum class CommandInsertResult {
    Stored,
    AlreadyStored,
    ResourceOccupied,
    CapacityExceeded,
    ScopeChanged,
}
