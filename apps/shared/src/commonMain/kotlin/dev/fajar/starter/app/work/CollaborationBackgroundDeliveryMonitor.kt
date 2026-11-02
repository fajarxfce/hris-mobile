package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select

/**
 * Own both children until delivery ends; the caller retains and releases the finite SDK reference.
 */
internal suspend fun monitorCollaborationBackgroundDelivery(
    lease: CollaborationBackgroundRuntimeLease,
    access: Flow<AppResult<CollaborationLocalAccess>>,
    expected: CollaborationLocalAccess,
    operation: suspend () -> SyncResult,
): SyncResult = coroutineScope {
    (lease.credentialFailures.value ?: lease.interruptions.value)?.let {
        return@coroutineScope syncFailure(it)
    }
    val stopped =
        async(start = CoroutineStart.UNDISPATCHED) {
            merge(
                    lease.credentialFailures.filterNotNull(),
                    lease.interruptions.filterNotNull(),
                    access
                        .map { owned ->
                            when (owned) {
                                is AppResult.Failed -> owned.failure
                                is AppResult.Success ->
                                    if (owned.value == expected) null
                                    else
                                        Failure(
                                            FailureKind.Unauthorized,
                                            "Chat ownership changed.",
                                            code = "client.session_changed",
                                        )
                            }
                        }
                        .filterNotNull(),
                )
                .buffer(0)
                .first()
        }
    val delivery = async(start = CoroutineStart.LAZY) { operation() }
    try {
        select {
            stopped.onAwait { failure ->
                delivery.cancelAndJoin()
                syncFailure(failure)
            }
            delivery.onAwait { result ->
                val failure =
                    if (stopped.isCompleted) stopped.await()
                    else lease.credentialFailures.value ?: lease.interruptions.value
                if (failure != null) syncFailure(failure) else result
            }
        }
    } finally {
        delivery.cancelAndJoin()
        stopped.cancelAndJoin()
    }
}
