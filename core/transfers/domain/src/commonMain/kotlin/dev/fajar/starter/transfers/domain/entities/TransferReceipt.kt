package dev.fajar.starter.transfers.domain.entities

import dev.fajar.starter.common.result.Failure

/** Queue persistence succeeds independently of a background scheduler request. */
data class TransferReceipt(val id: String, val schedulingFailure: Failure? = null)
