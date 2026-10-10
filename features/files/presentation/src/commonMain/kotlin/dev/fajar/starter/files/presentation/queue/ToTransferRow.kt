package dev.fajar.starter.files.presentation.queue

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.transfers.domain.entities.*

/** Drop byte/checkpoint churn before publishing a UI snapshot. */
fun Transfer.toRow() =
    TransferRow(
        id,
        file.name,
        if (direction == TransferDirection.Upload) AppString.Upload else AppString.Download,
        when (status) {
            TransferStatus.Staging -> AppString.TransferPreparing
            TransferStatus.Queued -> AppString.TransferQueued
            TransferStatus.Running -> AppString.TransferRunning
            TransferStatus.Paused -> AppString.TransferPaused
            TransferStatus.Failed -> AppString.TransferFailed
            TransferStatus.Completed -> AppString.TransferCompleted
        },
        ((if (status == TransferStatus.Staging) storedBytes else offset) * 100 /
                file.size.coerceAtLeast(1))
            .toInt()
            .coerceIn(0, 100),
        status in setOf(TransferStatus.Queued, TransferStatus.Running),
        status in setOf(TransferStatus.Paused, TransferStatus.Failed),
    )
