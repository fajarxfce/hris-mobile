package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Hosts without a native binding cannot create a substitute store or claim SDK readiness. */
class UnavailableCollaborationRuntimeRepository :
    CollaborationRuntimeRepository,
    CollaborationSpaceRuntimeRepository,
    CollaborationSpaceSyncRepository,
    CollaborationSpacePusherRepository,
    CollaborationSpaceOriginalHistoryRepository,
    CollaborationLocalDeviceIdentityRepository,
    CollaborationSdkInventoryRepository,
    CollaborationSdkPossessionRepository {
    override suspend fun read(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalHistorySnapshot> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun activate(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun synchronize(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun acquireOriginalQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalQueueLease> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun inspectOriginalQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalQueueSnapshot> = unavailable()

    override suspend fun isSpaceOpen(
        owner: CollaborationSpaceOwner,
        deviceId: String,
        operationId: String,
    ): AppResult<Boolean> = unavailable()

    override suspend fun installOriginal(
        owner: CollaborationSpaceOwner,
        original: CollaborationExistingRuntimeHandover,
        setup: CollaborationSpaceSetupRecord,
    ): AppResult<CollaborationSpaceRuntimeLease> = unavailable()

    override suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun readPrivateIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationSdkPossession?> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun inspect(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationSdkInventory> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun readLocalIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationLocalDeviceIdentity> {
        currentCoroutineContext().ensureActive()
        return unavailable()
    }

    override suspend fun handoverIntent(
        owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
        deviceId: String,
    ): AppResult<
        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent?
    > = unavailable()

    override suspend fun stageHandover(
        owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
        deviceId: String,
        intent: dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent,
    ): AppResult<
        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
    > = unavailable()

    override suspend fun updateRetention(
        retention: CollaborationRuntimeRetention
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun acquireBackground(
        owner: CollaborationOwner,
        deviceId: String,
        lifetimeMillis: Long,
    ): AppResult<CollaborationBackgroundRuntimeLease?> = unavailable()

    override suspend fun readOwnReadReceipts(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts> =
        unavailable()

    override suspend fun sendReadReceipt(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        eventId: String,
        visibility:
            dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
    ): AppResult<Unit> = unavailable()

    override suspend fun synchronizeDevice(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> = unavailable()

    override suspend fun activatePusher(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> = unavailable()

    override suspend fun selfIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationSelfIdentity?> = unavailable()

    override suspend fun prepareHistoryReopen(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<Boolean> = unavailable()

    override suspend fun readProviderHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        position: String?,
        untilPosition: String?,
    ): AppResult<CollaborationProviderHistoryPage> = unavailable()

    override suspend fun findHistoryHead(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationHistoryHead> = unavailable()

    override suspend fun readOriginalHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<CollaborationOriginalWindow> = unavailable()

    override suspend fun acquireMessageQueue(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationMessageQueueLease> = unavailable()

    override suspend fun isOpen(owner: CollaborationOwner, deviceId: String): AppResult<Boolean> =
        unavailable()

    override suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> = unavailable()

    override suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?> = unavailable()

    override suspend fun enterTimeline(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationTimelineLease> = unavailable()

    override suspend fun closeOtherOwners(retainedOwner: CollaborationOwner?): AppResult<Unit> =
        AppResult.Success(Unit)

    private fun unavailable() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Chat is not available on this platform yet.",
                code = "client.collaboration_runtime_unavailable",
            )
        )
}
