package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceOriginalQueueLease
import dev.fajar.starter.common.result.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Logical ownership of one already-held physical writer. No legacy send/enqueue handle escapes. */
internal class AndroidCollaborationSpaceOriginalQueueLease(
    override val runtime: CollaborationSpaceRuntimeReady,
    private val original: CollaborationMessageQueueLease,
    private val read: suspend () -> AppResult<CollaborationOriginalQueueSnapshot>,
    private val currentFailure: () -> Failure?,
) : CollaborationSpaceOriginalQueueLease {
    override val binding = original.owner.setupBinding()
    override val device = original.device
    private val execution = Mutex()
    private val closed = AtomicBoolean()

    override suspend fun snapshot(): AppResult<CollaborationSpaceOriginalQueueSnapshot> = owned {
        when (val observed = read()) {
            is AppResult.Failed -> observed
            is AppResult.Success -> {
                val value = observed.value
                if (value.binding != binding || value.device != device || value.messages.size > 128)
                    unresolved()
                else
                    AppResult.Success(
                        CollaborationSpaceOriginalQueueSnapshot(
                            runtime,
                            value.copy(messages = value.messages.toList()),
                        )
                    )
            }
        }
    }

    override suspend fun settleConfirmed(
        original: CollaborationQueuedOriginal,
        eventId: String,
    ): AppResult<CollaborationQueueSettlement> = owned {
        this.original.settleConfirmed(original, eventId)
    }

    override suspend fun resume(
        expected: CollaborationSpaceOriginalQueueSnapshot,
        onObserved: suspend (CollaborationQueueDelivery) -> AppResult<Unit>,
    ): AppResult<CollaborationQueueDelivery> = owned {
        val physical = expected.original
        if (
            expected.runtime != runtime ||
                physical.binding != binding ||
                physical.device != device ||
                physical.messages.size > 128
        )
            return@owned unresolved()
        val captured = physical.messages.toList()
        val byId = captured.associateBy { it.transactionId }
        if (byId.size != captured.size) return@owned unresolved()
        val observed = read()
        currentCoroutineContext().ensureActive()
        if (observed is AppResult.Failed) return@owned observed
        val current = (observed as AppResult.Success).value
        if (
            current.binding != binding ||
                current.device != device ||
                current.messages.size != captured.size ||
                current.messages.associateBy { it.transactionId } != byId
        )
            return@owned unresolved()
        failure()?.let {
            return@owned AppResult.Failed(it)
        }
        // The original native operation owns this callback even if its public waiter disappears.
        // Never drop an observed receipt merely because logical UI publication has been revoked.
        original.resume(byId.keys.toSet(), onObserved)
    }

    override suspend fun close(): AppResult<Unit> {
        closed.set(true)
        return original.close()
    }

    private suspend fun <T> owned(action: suspend () -> AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock())
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat queue is busy.",
                    code = "client.collaboration_queue_busy",
                )
            )
        try {
            failure()?.let {
                return AppResult.Failed(it)
            }
            val result = action()
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) return result
            failure()?.let {
                return AppResult.Failed(it)
            }
            return result
        } finally {
            execution.unlock()
        }
    }

    private fun failure(): Failure? =
        if (closed.get())
            Failure(
                FailureKind.Unavailable,
                "Chat queue is closed.",
                code = "client.collaboration_queue_closed",
            )
        else currentFailure()

    private fun unresolved() =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "Original messages changed.",
                code = "client.collaboration_queue_unresolved",
            )
        )

    override fun toString() = "AndroidCollaborationSpaceOriginalQueueLease([redacted])"
}
