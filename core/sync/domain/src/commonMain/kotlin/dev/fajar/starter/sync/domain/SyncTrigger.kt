package dev.fajar.starter.sync.domain

enum class SyncTrigger {
    /** A durable request, its continuation, or its retry. Delivery may be duplicated. */
    Requested,
    /** Host startup or periodic cadence may discover work without an existing request. */
    Scheduled,
}
