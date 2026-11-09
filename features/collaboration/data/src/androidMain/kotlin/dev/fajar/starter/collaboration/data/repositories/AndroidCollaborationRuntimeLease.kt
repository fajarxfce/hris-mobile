package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.api.CollaborationPusherApi
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHttpObservation
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkClientSource
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationTimelineSource
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreLease
import dev.fajar.starter.collaboration.data.datasources.createAndroidCollaborationSync
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalDeviceIdentity
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageDevice
import dev.fajar.starter.collaboration.domain.entities.CollaborationOriginalQueueSnapshot
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationProviderHistoryPage
import dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility
import dev.fajar.starter.collaboration.domain.entities.CollaborationReadinessSignal
import dev.fajar.starter.collaboration.domain.policies.COLLABORATION_READINESS_CONTROL_TYPE
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
    pusherApi: CollaborationPusherApi,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val authentication: AndroidSdkAuthRegistration? = null,
    private val released: (AndroidCollaborationRuntimeLease) -> Unit,
) : CollaborationRuntimeLease {
    override val deviceId = record.deviceId
    override val installedOperationId = record.bootstrapOperationId
    private val messageOperationId = record.messageOperationId
    private val installedGateway = SdkInstalledGateway(record)

    internal fun acceptsReplacement(
        command: dev.fajar.starter.collaboration.domain.entities.CollaborationSetupCommand,
        gateway: dev.fajar.starter.collaboration.domain.entities.CollaborationGateway,
    ): Boolean = installedGateway.acceptsReplacement(command, gateway)

    override val credentialFailures = persistence.failures
    private val lifetimeJob = SupervisorJob()
    private val lifetime = CoroutineScope(lifetimeJob + dispatcher)
    private val shutdown = SdkStoreShutdown(lifetime) { releaseResources() }
    private val stopRequested = AtomicBoolean()
    private val acquisitionComplete = AtomicBoolean()
    private val acquisitionFinished = CompletableDeferred<Unit>()
    private val published = AtomicBoolean()
    private val httpObservationStarted = AtomicBoolean()
    private var httpObservationJob: Job? = null
    private val readinessStarted = AtomicBoolean()
    private val matrixUserId = record.matrixUserId
    private var readiness: AndroidSdkReadinessRegistration? = null
    private val sync =
        SdkSyncLifecycle(lifetime) { createAndroidCollaborationSync(nativeClient, lifetime) }
    private val children = SdkChildResources(lifetime)
    private val pusher =
        AndroidCollaborationPusher(
            nativeClient,
            record.matrixUserId,
            record.matrixDeviceId,
            record.homeserverUrl,
            children,
            lifetime,
            pusherApi,
        ) {
            credentialFailures.value
                ?: sync.failures.value?.takeIf {
                    it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                }
                ?: if (stopRequested.get()) syncStopped().failure else null
        }
    private val readReceipts =
        AndroidCollaborationReadReceipts(nativeClient, record.matrixUserId, children, lifetime) {
            credentialFailures.value
                ?: sync.failures.value?.takeIf {
                    it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                }
                ?: if (stopRequested.get()) syncStopped().failure else null
        }
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
            messageOperationId,
            record.matrixUserId,
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

    /** Acquisition owns registration before restore/sync; shutdown joins acquisition first. */
    internal fun startReadinessObservation(
        source: AndroidCollaborationSdkClientSource,
        offer: (CollaborationReadinessSignal) -> Unit,
    ) {
        if (closingRequested || !readinessStarted.compareAndSet(false, true)) return
        val registration =
            AndroidSdkReadinessRegistration(
                owner,
                deviceId,
                matrixUserId,
                { !closingRequested },
                offer,
            )
        readiness = registration
        registration.attach(
            source.observeCustomToDevice(
                nativeClient,
                COLLABORATION_READINESS_CONTROL_TYPE,
                registration,
            )
        )
    }

    /**
     * Acquisition invokes this before restore. Shutdown waits for acquisition before reading its
     * job.
     */
    internal fun startHttpObservation(observation: AndroidCollaborationHttpObservation?) {
        observation ?: return
        if (closingRequested || !httpObservationStarted.compareAndSet(false, true)) return
        httpObservationJob =
            lifetime.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    observation.observe(nativeClient)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Optional diagnostics must not cancel sync/delivery or change runtime
                    // readiness.
                    // The adapter retains incomplete captures without exposing raw SDK errors here.
                }
            }
    }

    /** Registry invalidation is synchronous; its caller drains outside the registry monitor. */
    internal fun requestClose() {
        stopRequested.set(true)
        readiness?.deactivate()
    }

    internal val availableForEntry
        get() = acquisitionComplete.get() && published.get() && !stopRequested.get()

    override fun publish(): AppResult<Unit> {
        if (stopRequested.get()) return syncStopped()
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (!acquisitionComplete.get())
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat is still opening.",
                    code = "client.collaboration_runtime_opening",
                )
            )
        published.set(true)
        return if (stopRequested.get()) syncStopped() else AppResult.Success(Unit)
    }

    /** No active queue writer is interrupted to reclaim this client's retained history caches. */
    internal suspend fun prepareHistoryReopen(
        roomId: String,
        anchorEventId: String,
    ): AppResult<Boolean> {
        if (closingRequested) return closedForHistory()
        if (!availableForEntry)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat is still opening.",
                    code = "client.collaboration_runtime_opening",
                )
            )
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (!historyWindows.needsReopen(roomId, anchorEventId)) return AppResult.Success(false)
        return messageQueues.whileWriterIdle {
            credentialFailures.value?.let {
                return@whileWriterIdle AppResult.Failed(it)
            }
            closedForHistory()
        }
    }

    private suspend fun closedForHistory(): AppResult<Boolean> =
        when (val result = close()) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(true)
        }

    /** Native readiness precedes the owning use case's separate admission/publication step. */
    internal fun completeAcquisition() {
        check(!stopRequested.get())
        check(acquisitionComplete.compareAndSet(false, true))
        finishAcquisition()
    }

    /** Repository finally also signals unsuccessful acquisition before attempting its drain. */
    internal fun finishAcquisition() {
        acquisitionFinished.complete(Unit)
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
                persistence.persistAuthenticationFailure()
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
    suspend fun startSync(): AppResult<Unit> = syncOperation { sync.start() }

    /** No room join or queue resume; completion belongs to the current owned SDK sync. */
    suspend fun synchronizeDevice(): AppResult<Unit> = syncOperation { sync.awaitFirstSync() }

    private suspend fun syncOperation(operation: suspend () -> AppResult<Unit>): AppResult<Unit> {
        if (stopRequested.get()) return syncStopped()
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        val result = operation()
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

    /** Original local queue facts only; no send capability escapes through this operation. */
    internal suspend fun inspectOriginalQueue(
        roomId: String
    ): AppResult<CollaborationOriginalQueueSnapshot> = messageQueues.inspectOriginals(roomId)

    internal suspend fun readHeldOriginalQueue(
        queue: CollaborationMessageQueueLease
    ): AppResult<CollaborationOriginalQueueSnapshot> = messageQueues.readOriginals(queue)

    /** Borrowed transport reads only a head; this runtime owns cancellation of that request. */
    suspend fun findHistoryHead(roomId: String): AppResult<String?> = historyReads.read(roomId)

    suspend fun activatePusher(): AppResult<Unit> = pusher.activate()

    /** SDK-local metadata; this neither starts sync nor compares event ordering. */
    suspend fun readOwnReadReceipts(roomId: String): AppResult<CollaborationOwnReadReceipts> =
        readReceipts.readOwn(roomId)

    /**
     * The caller must freshly admit the exact owner/device/room and retain a durable read intent.
     */
    suspend fun sendReadReceipt(
        roomId: String,
        eventId: String,
        visibility: CollaborationReadReceiptVisibility,
    ): AppResult<Unit> = readReceipts.send(roomId, eventId, visibility)

    /**
     * Provider references only. The admitted caller owns durable progress and content acquisition.
     */
    suspend fun readProviderHistory(
        roomId: String,
        position: String?,
        untilPosition: String? = null,
    ): AppResult<CollaborationProviderHistoryPage> =
        when (val result = historyReads.page(roomId, position, untilPosition)) {
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
                        untilPosition,
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
            children.acquire(
                source,
                {
                    safeAndroidCollaborationCall(AndroidCollaborationOperation.Timeline) {
                        it.open()
                    }
                },
                { it.stop() },
            )
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
                                CollaborationMessageDevice(
                                    owner.nativeSessionId,
                                    deviceId,
                                    messageOperationId,
                                    roomId,
                                ),
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

    /** The child owner prevents client destruction while the local FFI observation is pending. */
    internal suspend fun localDeviceIdentity(
        source: AndroidCollaborationSdkClientSource
    ): AppResult<CollaborationLocalDeviceIdentity> {
        if (!availableForEntry) return syncStopped()
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        val observation = LocalIdentityObservation()
        val acquired =
            children.acquire(
                observation,
                { target ->
                    safeStorageCall {
                        target.value = source.identity(nativeClient).toLocalDeviceIdentity()
                    }
                },
                {},
            )
        if (acquired is AppResult.Failed) return acquired
        val child = (acquired as AppResult.Success).value
        val closed: AppResult<Unit>
        val result =
            try {
                safeStorageCall { requireNotNull(observation.value) }
            } finally {
                closed = child.close()
            }
        if (closed is AppResult.Failed) return closed
        if (!availableForEntry) return syncStopped()
        credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        return result
    }

    override suspend fun close(): AppResult<Unit> {
        stopRequested.set(true)
        readiness?.deactivate()
        return shutdown.close()
    }

    private suspend fun releaseResources(): SdkShutdownAttempt {
        // Never pause/destroy the client or unlock its directory while restore still uses it.
        acquisitionFinished.await()
        val readinessResult = safeStorageCall {
            readiness?.close()
            Unit
        }
        httpObservationJob?.cancelAndJoin()
        authentication?.deactivate()
        credentialWatcher.cancelAndJoin()
        syncWatcher.cancelAndJoin()
        val rejection = persistence.persistAuthenticationFailure()
        if (rejection is AppResult.Failed) return SdkShutdownAttempt(rejection, released = false)
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
        var credentialResult: AppResult<Unit> = AppResult.Success(Unit)
        val result = safeStorageCall {
            try {
                try {
                    authentication?.close()
                } finally {
                    nativeClient.close()
                }
            } finally {
                // A refresh already in flight may finish while children/initialization/native
                // stores drain. Its synchronous session callback still owns this protected
                // record. Close that writer only after the native handle is released.
                try {
                    credentialResult = persistence.close()
                } finally {
                    directory.close()
                }
            }
        }
        released(this)
        lifetimeJob.complete()
        return SdkShutdownAttempt(
            if (result is AppResult.Failed) result
            else if (readinessResult is AppResult.Failed) readinessResult else credentialResult,
            released = true,
        )
    }

    override fun toString() = "AndroidCollaborationRuntimeLease([redacted])"
}

private class LocalIdentityObservation {
    var value: CollaborationLocalDeviceIdentity? = null
}
