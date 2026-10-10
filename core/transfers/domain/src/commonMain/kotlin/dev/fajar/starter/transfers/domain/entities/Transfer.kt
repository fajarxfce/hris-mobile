package dev.fajar.starter.transfers.domain.entities

import dev.fajar.starter.common.result.FailureKind

data class Transfer(
    val id: String,
    val direction: TransferDirection,
    val file: TransferFile,
    val createdAtEpochMillis: Long,
    val resourceId: String? = null,
    val resourceVersion: String? = null,
    val offset: Long = 0,
    val storedBytes: Long = 0,
    val status: TransferStatus = TransferStatus.Staging,
    val checkpointVersion: Long = 0,
    val failureKind: FailureKind? = null,
)
