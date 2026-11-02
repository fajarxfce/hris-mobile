package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*

/** Hosts without a native binding cannot create a substitute store or claim SDK readiness. */
class UnavailableCollaborationRuntimeRepository : CollaborationRuntimeRepository {
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
