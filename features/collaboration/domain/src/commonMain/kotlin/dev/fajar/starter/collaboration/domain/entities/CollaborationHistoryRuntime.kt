package dev.fajar.starter.collaboration.domain.entities

/** Preparing capacity never starts synchronization, acquires a window or enrolls a new device. */
enum class CollaborationHistoryRuntimePreparation {
    Available,
    Reopened,
    SetupRequired,
}
