package dev.fajar.starter.sync.domain

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind

/** Application retry policy, independent of the platform scheduler. */
fun syncFailure(failure: Failure): SyncResult =
    when (failure.kind) {
        FailureKind.Network,
        FailureKind.Timeout,
        FailureKind.Service -> SyncResult.Retry(failure)
        else -> SyncResult.Blocked(failure)
    }
