package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceMessageQueueLease
import dev.fajar.starter.common.result.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** A separate staging handle for one held physical writer. It never resumes native delivery. */
internal class AndroidCollaborationSpaceMessageQueueLease(
    override val runtime: CollaborationSpaceRuntimeReady,
    private val original: CollaborationMessageQueueLease,
    private val read: suspend () -> AppResult<CollaborationOriginalQueueSnapshot>,
    private val currentFailure: () -> Failure?,
) : CollaborationSpaceMessageQueueLease {
    override val binding = original.owner.setupBinding()
    override val device = original.device
    private val execution = Mutex()
    private val closed = AtomicBoolean()

    override suspend fun snapshot(): AppResult<CollaborationSpaceOriginalQueueSnapshot> = owned {
        when (val result = read()) {
            is AppResult.Failed -> result
            is AppResult.Success -> {
                val value = result.value
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

    override suspend fun prepareText(
        logicalId: String,
        text: String,
        replyToEventId: String?,
        forwarded: Boolean,
        editTarget: CollaborationEditTarget?,
    ): AppResult<CollaborationMessageOrigin> = owned {
        original.prepareText(logicalId, text, replyToEventId, forwarded, editTarget)
    }

    override suspend fun enqueue(
        expected: CollaborationSpaceOriginalQueueSnapshot,
        logicalId: String,
        text: String,
        origin: CollaborationMessageOrigin,
        replyToEventId: String?,
        forwarded: Boolean,
        editTarget: CollaborationEditTarget?,
    ): AppResult<Unit> = owned {
        val physical = expected.original
        if (
            expected.runtime != runtime ||
                physical.binding != binding ||
                physical.device != device ||
                physical.messages.size >= 128
        )
            return@owned unresolved()
        val captured = physical.messages.toList()
        val byId = captured.associateBy { it.transactionId }
        if (byId.size != captured.size || captured.any { it.logicalId == logicalId })
            return@owned unresolved()
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
        original.enqueue(logicalId, text, origin, replyToEventId, forwarded, editTarget)
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

    override fun toString() = "AndroidCollaborationSpaceMessageQueueLease([redacted])"
}
