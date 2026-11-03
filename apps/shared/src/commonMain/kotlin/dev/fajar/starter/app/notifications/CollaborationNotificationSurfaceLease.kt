package dev.fajar.starter.app.notifications

import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.notifications.data.errors.safeNotificationCall
import dev.fajar.starter.notifications.data.mappers.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** Mechanical ownership only. HRIS source/action admission still belongs to the coordinator. */
class CollaborationNotificationSurfaceLease
internal constructor(
    private val source: ActionNotificationDisplaySource,
    private val current: () -> Boolean,
) {
    private val active = MutableStateFlow(true)
    private val native = Mutex()
    private val attempted = mutableSetOf<ActionNotificationReference>()

    suspend fun show(payload: ActionNotificationPayload): AppResult<ActionNotificationSubmission> {
        if (!validActionNotificationPayload(payload)) return surfaceFailure(FailureKind.Validation)
        return call {
            if (payload.reference !in attempted && attempted.size >= 4)
                return@call surfaceFailure(FailureKind.Unavailable)
            attempted += payload.reference
            safeNotificationCall { source.show(payload) }
        }
    }

    suspend fun remove(
        reference: ActionNotificationReference
    ): AppResult<ActionNotificationRemoval> {
        if (!validActionNotificationReference(reference))
            return surfaceFailure(FailureKind.Validation)
        return call {
            if (reference !in attempted && attempted.size >= 4)
                return@call surfaceFailure(FailureKind.Unavailable)
            // Explicit removal can fail or be cancelled after native submission too. Register
            // its exact cleanup obligation before IPC, even when this turn never showed a card.
            attempted += reference
            safeNotificationCall { source.remove(reference) }
        }
    }

    private suspend fun <T> call(operation: suspend () -> AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        if (!owned()) return surfaceFailure(FailureKind.Unauthorized)
        if (!native.tryLock()) return surfaceFailure(FailureKind.Unavailable)
        return try {
            if (!owned()) return surfaceFailure(FailureKind.Unauthorized)
            val result = operation()
            currentCoroutineContext().ensureActive()
            if (!owned()) surfaceFailure(FailureKind.Unauthorized) else result
        } finally {
            native.unlock()
        }
    }

    private suspend fun owned() =
        active.value &&
            current() &&
            currentCoroutineContext()[NotificationSurfaceExecution]?.lease === this

    internal fun close() {
        active.value = false
    }

    /**
     * Called only after the request and its children have joined, while global serialization holds.
     */
    internal suspend fun compensate(): Boolean {
        var complete = true
        for (reference in attempted) {
            val removed = safeNotificationCall { source.remove(reference) }
            if (removed is AppResult.Failed) complete = false
        }
        return complete
    }
}

internal class NotificationSurfaceExecution(val lease: CollaborationNotificationSurfaceLease) :
    AbstractCoroutineContextElement(NotificationSurfaceExecution) {
    companion object : CoroutineContext.Key<NotificationSurfaceExecution>
}

internal fun surfaceFailure(kind: FailureKind) =
    AppResult.Failed(
        Failure(
            kind,
            "The chat notification is unavailable.",
            code = "client.collaboration_notification_surface_unavailable",
        )
    )
