package dev.fajar.starter.transfers.domain.entities

enum class TransferStatus {
    Staging,
    Queued,
    Running,
    Paused,
    Failed,
    Completed,
}
