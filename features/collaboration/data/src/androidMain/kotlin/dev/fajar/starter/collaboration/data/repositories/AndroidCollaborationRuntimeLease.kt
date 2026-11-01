package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationTimelineSource
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreLease
import dev.fajar.starter.collaboration.data.datasources.createAndroidCollaborationSync
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationProviderHistoryPage
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Room

/** Platform data access only; feature pages/ViewModels never receive the raw SDK client. */
class AndroidCollaborationRuntimeLease
internal constructor(
    override val owner: CollaborationOwner,
    val nativeClient: Client,
    private val directory: CollaborationSdkStoreLease,
    private val persistence: SdkSessionPersistence,
    record: CollaborationSdkRecord,
    historyApi: CollaborationHistoryApi,
    private val released: (AndroidCollaborationRuntimeLease) -> Unit,
) : CollaborationRuntimeLease {
    override val deviceId = record.deviceId
    override val installedOperationId = record.bootstrapOperationId
    override val credentialFailures = persistence.failures
    private val lifetimeJob = SupervisorJob()
    private val lifetime = CoroutineScope(lifetimeJob + Dispatchers.Default)
    private val shutdown = SdkStoreShutdown(lifetime) { releaseResources() }
    private val stopRequested = AtomicBoolean()
    private val acquisitionComplete = AtomicBoolean()
    private val sync =
        SdkSyncLifecycle(lifetime) { createAndroidCollaborationSync(nativeClient, lifetime) }
    private val children = SdkChildResources(lifetime)
    private val historyWindows =
        AndroidCollaborationHistoryWindows(nativeClient, children) {
            credentialFailures.value
                ?: sync.failures.value?.takeIf {
                    it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                }
                ?: if (stopRequested.get()) syncStopped().failure else null
        }
    private val historyReads =
        AndroidCollaborationHistoryReads(
            nativeClient,
            record.matrixUserId,
            record.matrixDeviceId,
            record.homeserverUrl,
            children,
            lifetime,
            historyApi,
        ) {
            credentialFailures.value
                ?: sync.failures.value?.takeIf {
                    it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                }
                ?: if (stopRequested.get()) syncStopped().failure else null
        }
    private val messageQueues =
        AndroidCollaborationMessageQueues(
            owner,
            deviceId,
            installedOperationId,
            nativeClient,
            lifetime,
            children,
            onInterrupted = {
                if (stopRequested.compareAndSet(false, true)) lifetime.launch { shutdown.close() }
            },
        ) {
            credentialFailures.value
                ?: sync.failures.value?.takeIf {
                    it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                }
                ?: if (stopRequested.get()) syncStopped().failure else null
        }
    internal val closingRequested
        get() = stopRequested.get()

    internal val availableForEntry
        get() = acquisitionComplete.get() && !stopRequested.get()

    /** Publication follows paused SDK restore and durable initialization. */
    internal fun completeAcquisition() {
        check(!stopRequested.get())
        check(acquisitionComplete.compareAndSet(false, true))
    }

    private val initialization =
        lifetime.async(start = CoroutineStart.LAZY) {
            nativeClient.encryption().use { it.waitForE2eeInitializationTasks() }
        }
    private val syncWatcher =
        lifetime.launch(start = CoroutineStart.LAZY) {
            sync.failures.filterNotNull().collect { failure ->
                if (failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
                    safeStorageCall { nativeClient.enableAllSendQueues(false) }
                    children.close()
                }
            }
        }
    private val credentialWatcher =
        lifetime.launch(start = CoroutineStart.LAZY) {
            credentialFailures.filterNotNull().collect {
                safeStorageCall { nativeClient.enableAllSendQueues(false) }
                sync.stop()
                children.close()
            }
        }

    internal fun startMonitoring() {
        initialization.start()
        credentialWatcher.start()
        syncWatcher.start()
    }

    /** Platform data entry point; the owning operation must hold current HRIS admission. */
    suspend fun startSync(): AppResult<Unit> {
        if (stopRequested.get()) return syncStopped()
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        val result = sync.start()
        currentCoroutineContext().ensureActive()
        if (stopRequested.get())
            return AppResult.Failed(
                collaborationSdkAcquisitionFailure(result, syncStopped().failure)
            )
        credentialFailures.value?.let {
            // The mandatory credential watcher owns termination even if this waiter disappears.
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, it))
        }
        return result
    }

    /** Opens an already-known room; does not join, restore, or resume message delivery. */
    suspend fun openTimeline(roomId: String): AppResult<AndroidCollaborationTimelineLease> =
        acquireTimeline(roomId) { requireNotNull(nativeClient.getRoom(roomId)) }

    /** The admitted entry operation may join; the resulting child still belongs to this parent. */
    suspend fun enterTimeline(roomId: String): AppResult<AndroidCollaborationTimelineLease> =
        acquireTimeline(roomId) { nativeClient.joinRoomById(roomId) }

    /** Explicit queue entry acquires a child writer; SDK delivery stays paused. */
    suspend fun acquireMessageQueue(roomId: String): AppResult<CollaborationMessageQueueLease> =
        messageQueues.acquire(roomId)

    /** Borrowed transport reads only a head; this runtime owns cancellation of that request. */
    suspend fun findHistoryHead(roomId: String): AppResult<String?> = historyReads.read(roomId)

    /**
     * Provider references only. The admitted caller owns durable progress and content acquisition.
     */
    suspend fun readProviderHistory(
        roomId: String,
        position: String?,
    ): AppResult<CollaborationProviderHistoryPage> =
        when (val result = historyReads.page(roomId, position)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(
                    CollaborationProviderHistoryPage(
                        owner,
                        deviceId,
                        roomId,
                        position,
                        result.value.start,
                        result.value.next,
                        result.value.events.toList(),
                    )
                )
        }

    /** Explicit admitted acquisition; no join, bootstrap, send-queue enable or provider cursor. */
    suspend fun readOriginalHistory(
        roomId: String,
        anchorEventId: String,
    ): AppResult<AndroidCollaborationHistoryWindow> = historyWindows.read(roomId, anchorEventId)

    private suspend fun acquireTimeline(
        roomId: String,
        acquireRoom: suspend () -> Room,
    ): AppResult<AndroidCollaborationTimelineLease> {
        if (stopRequested.get()) return syncStopped()
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (
            !roomId.startsWith('!') ||
                roomId.length !in 2..255 ||
                roomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid chat room."))
        val source = AndroidCollaborationTimelineSource(acquireRoom)
        val result =
            children.acquire(source, { safeAndroidCollaborationCall { it.open() } }, { it.stop() })
        var handedOver = false
        try {
            currentCoroutineContext().ensureActive()
            if (stopRequested.get())
                return AppResult.Failed(
                    collaborationSdkAcquisitionFailure(result, syncStopped().failure)
                )
            credentialFailures.value?.let {
                return AppResult.Failed(collaborationSdkAcquisitionFailure(result, it))
            }
            return when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    AppResult.Success(
                            AndroidCollaborationTimelineLease(
                                owner,
                                deviceId,
                                roomId,
                                result.value,
                                source.updates,
                                sync.failures,
                            )
                        )
                        .also { handedOver = true }
            }
        } finally {
            if (!handedOver && result is AppResult.Success) result.value.close()
        }
    }

    private fun syncStopped() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Chat synchronization has stopped.",
                code = "client.collaboration_sync_stopped",
            )
        )

    override suspend fun retryCredentialWrite() = persistence.retryPending()

    override suspend fun close(): AppResult<Unit> {
        stopRequested.set(true)
        return shutdown.close()
    }

    private suspend fun releaseResources(): SdkShutdownAttempt {
        val credentialResult = persistence.close()
        credentialWatcher.cancelAndJoin()
        syncWatcher.cancelAndJoin()
        safeStorageCall { nativeClient.enableAllSendQueues(false) }
        val syncStopped = sync.stop()
        if (syncStopped is AppResult.Failed)
            return SdkShutdownAttempt(syncStopped, released = false)
        val childrenStopped = children.close()
        if (childrenStopped is AppResult.Failed)
            return SdkShutdownAttempt(childrenStopped, released = false)
        initialization.start()
        initialization.join()
        // Disabling queues does not abort an in-flight send. Pause drains and closes
        // the SDK store pools so delayed native work cannot write after our unlock.
        val drained = safeStorageCall { nativeClient.pause() }
        if (drained is AppResult.Failed) return SdkShutdownAttempt(drained, released = false)
        val result = safeStorageCall {
            try {
                nativeClient.close()
            } finally {
                directory.close()
            }
        }
        released(this)
        lifetimeJob.complete()
        return SdkShutdownAttempt(
            if (result is AppResult.Failed) result else credentialResult,
            released = true,
        )
    }

    override fun toString() = "AndroidCollaborationRuntimeLease([redacted])"
}
