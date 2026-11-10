package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationRawEventSource
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.mappers.toAcquiredEvent
import dev.fajar.starter.collaboration.domain.entities.CollaborationEventSnapshot
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestFailureException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Finite reads share the native parent's child ownership, including late/cancelled acquisition. */
internal class AndroidCollaborationEventReads(
    private val owner: CollaborationOwner,
    private val deviceId: String,
    private val source: CollaborationRawEventSource,
    private val children: SdkChildResources,
    private val failure: () -> Failure?,
) {
    suspend fun read(roomId: String, eventId: String): AppResult<CollaborationEventSnapshot> {
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (
            !validCollaborationEventId(eventId) ||
                !roomId.startsWith('!') ||
                roomId.length !in 2..255 ||
                roomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid chat event."))
        val captured = AtomicReference<CollaborationEventSnapshot?>()
        val acquired =
            children.acquire(
                captured,
                { result ->
                    safeAndroidCollaborationCall(AndroidCollaborationOperation.RawEvent) {
                        failure()?.let { throw RequestFailureException(it) }
                        val raw = source.read(roomId, eventId)
                        failure()?.let { throw RequestFailureException(it) }
                        check(raw.roomId == roomId && raw.eventId == eventId)
                        val event = raw.toAcquiredEvent()
                        failure()?.let { throw RequestFailureException(it) }
                        result.set(
                            CollaborationEventSnapshot(
                                owner,
                                deviceId,
                                roomId,
                                raw.roomJoined,
                                raw.roomEncrypted,
                                event,
                            )
                        )
                    }
                },
                { it.set(null) },
            )
        if (acquired is AppResult.Failed) {
            currentCoroutineContext().ensureActive()
            return failure()?.let {
                AppResult.Failed(collaborationSdkAcquisitionFailure(acquired, it))
            } ?: acquired
        }
        val child = (acquired as AppResult.Success).value
        val disposed: AppResult<Unit>
        val result =
            try {
                currentCoroutineContext().ensureActive()
                val event = captured.getAndSet(null)
                if (event == null)
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "Chat event is unavailable.",
                            code = "client.collaboration_event_interrupted",
                        )
                    )
                else AppResult.Success(event)
            } finally {
                disposed = child.close()
            }
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, it))
        }
        if (disposed is AppResult.Failed)
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, disposed.failure))
        return result
    }
}
