package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationHistoryWindow
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*

/** Borrows the already protected fixture runtime. Its outer owner still owns full shutdown. */
internal class HrisProofRuntimeAccess(private val runtime: AndroidCollaborationRuntimeLease) :
    CollaborationRuntimeRepository {
    var historyWindow: AndroidCollaborationHistoryWindow? = null
        private set

    override suspend fun readOriginalHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<CollaborationOriginalWindow> {
        check(owner == runtime.owner && deviceId == runtime.deviceId)
        check(historyWindow == null)
        val result = runtime.readOriginalHistory(matrixRoomId, anchorEventId)
        if (result is AppResult.Failed) return result
        val window = (result as AppResult.Success).value.also { historyWindow = it }
        return AppResult.Success(
            CollaborationOriginalWindow(
                owner,
                deviceId,
                matrixRoomId,
                anchorEventId,
                window.originals,
                window.unavailableEventIds,
            )
        )
    }

    override suspend fun acquireMessageQueue(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationMessageQueueLease> {
        check(owner == runtime.owner)
        val result = runtime.acquireMessageQueue(matrixRoomId)
        if (result is AppResult.Success) {
            check(result.value.device.deviceId == deviceId)
            val competitor = runtime.acquireMessageQueue(matrixRoomId)
            check(
                competitor is AppResult.Failed &&
                    competitor.failure.code == "client.collaboration_queue_busy"
            )
        }
        return result
    }

    override suspend fun isOpen(owner: CollaborationOwner, deviceId: String): AppResult<Boolean> =
        error("No implicit owner lookup")

    override suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> = error("No enrollment")

    override suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?> = error("No implicit restore")

    override suspend fun enterTimeline(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationTimelineLease> = error("No implicit join")

    override suspend fun closeOtherOwners(retainedOwner: CollaborationOwner?): AppResult<Unit> =
        error("Outer proof owns shutdown")
}
