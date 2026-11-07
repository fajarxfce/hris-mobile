package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationMessageQueueSource
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageDevice
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.Client

/** All rooms on one protected runtime share one writer, including unfinished shutdowns. */
internal class AndroidCollaborationMessageQueues(
    private val owner: CollaborationOwner,
    private val deviceId: String,
    private val bootstrapId: String,
    private val matrixSender: String,
    private val client: Client,
    private val lifetime: CoroutineScope,
    private val children: SdkChildResources,
    private val onInterrupted: () -> Unit,
    private val failure: () -> Failure?,
) {
    private val occupied = AtomicBoolean()

    /** One bounded observation holds the same writer as delivery and always releases its child. */
    suspend fun inspectOriginals(roomId: String): AppResult<CollaborationOriginalQueueSnapshot> {
        var lease: CollaborationMessageQueueLease? = null
        var primary: Throwable? = null
        var result: AppResult<CollaborationOriginalQueueSnapshot> =
            unavailable("client.collaboration_queue_unresolved")
        try {
            val acquired = acquire(roomId)
            if (acquired is AppResult.Success) lease = acquired.value
            currentCoroutineContext().ensureActive()
            result =
                when (acquired) {
                    is AppResult.Failed -> acquired
                    is AppResult.Success -> readOriginals(acquired.value)
                }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    val closed = lease?.close()
                    if (result is AppResult.Success && closed is AppResult.Failed) result = closed
                } catch (failure: Throwable) {
                    if (primary != null) primary.addSuppressed(failure)
                    else if (result is AppResult.Success) throw failure
                }
            }
        }
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(it)
        }
        return result
    }

    internal suspend fun readOriginals(
        lease: CollaborationMessageQueueLease
    ): AppResult<CollaborationOriginalQueueSnapshot> {
        val before = lease.snapshot()
        if (before is AppResult.Failed) return before
        val original = lease.originals()
        if (original is AppResult.Failed) return original
        val after = lease.snapshot()
        if (after is AppResult.Failed) return after
        return safeStorageCall {
            val messages = (original as AppResult.Success).value
            val ids = messages.map { it.transactionId }.toSet()
            check(messages.size <= 128 && ids.size == messages.size)
            check(
                ids == (before as AppResult.Success).value &&
                    ids == (after as AppResult.Success).value
            )
            CollaborationOriginalQueueSnapshot(
                owner.setupBinding(),
                lease.device,
                messages.toList(),
            )
        }
    }

    /** Uses the writer's own exclusion so a close cannot race a new or unfinished queue owner. */
    suspend fun <T> whileWriterIdle(operation: suspend () -> AppResult<T>): AppResult<T> {
        if (!occupied.compareAndSet(false, true))
            return unavailable("client.collaboration_queue_busy")
        return try {
            operation()
        } finally {
            occupied.set(false)
        }
    }

    suspend fun acquire(roomId: String): AppResult<CollaborationMessageQueueLease> {
        failure()?.let {
            return AppResult.Failed(it)
        }
        val device =
            CollaborationMessageDevice(owner.nativeSessionId, deviceId, bootstrapId, roomId)
        if (!validCollaborationMessageDevice(device))
            return unavailable("client.collaboration_invalid_queue")
        var ownsWriter = false
        val source = AndroidCollaborationMessageQueueSource {
            requireNotNull(client.getRoom(roomId))
        }
        val operations = SdkQueueOperations(lifetime, onInterrupted)
        val acquired =
            children.acquire(
                source,
                initialize = {
                    if (!occupied.compareAndSet(false, true))
                        unavailable("client.collaboration_queue_busy")
                    else {
                        ownsWriter = true
                        failure()?.let { reason -> AppResult.Failed(reason) }
                            ?: safeAndroidCollaborationCall(
                                AndroidCollaborationOperation.QueueOpen
                            ) {
                                it.open()
                            }
                    }
                },
                release = {
                    // Pause before waiting for the owned operation; disposal cannot leave a
                    // delivery window enabled while an acknowledgement is still pending.
                    it.requestStop()
                    operations.stop()
                    it.stop()
                    if (ownsWriter) {
                        ownsWriter = false
                        occupied.set(false)
                    }
                },
            )
        var handedOver = false
        try {
            currentCoroutineContext().ensureActive()
            failure()?.let {
                return AppResult.Failed(collaborationSdkAcquisitionFailure(acquired, it))
            }
            if (acquired is AppResult.Failed) return acquired
            return AppResult.Success(
                    AndroidCollaborationMessageQueueLease(
                        owner,
                        device,
                        matrixSender,
                        source,
                        operations,
                        (acquired as AppResult.Success).value,
                        failure,
                    )
                )
                .also { handedOver = true }
        } finally {
            if (!handedOver && acquired is AppResult.Success) acquired.value.close()
        }
    }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat queue is unavailable.", code = code)
        )
}
