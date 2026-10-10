package dev.fajar.starter.sync.domain

import dev.fajar.starter.common.result.Failure

sealed interface SyncResult {
    data object Complete : SyncResult

    /** Null means a bounded batch finished and more durable work remains. */
    data class Retry(val failure: Failure? = null) : SyncResult

    /** No backoff retry for this execution. A later trigger can re-evaluate the failure. */
    data class Blocked(val failure: Failure) : SyncResult
}
