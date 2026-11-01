package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** One explicitly requested, live-admitted original window. No cursor advancement or retry. */
class AcquireCollaborationHistory(
    private val history: CollaborationHistoryRepository,
    private val lists: CollaborationListRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
        anchorEventId: String,
    ): AppResult<CollaborationHistoryAcquisition> {
        if (
            !validCollaborationMessageId(deviceId) ||
                !validCollaborationMessageId(conversationId) ||
                !validCollaborationEventId(anchorEventId)
        )
            return failed("client.collaboration_invalid_history", FailureKind.Validation)
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        continuation(workspaceId, owner, null).let { if (it is AppResult.Failed) return it }
        val admitted = admitRoom(workspaceId, owner, deviceId, conversationId)
        if (admitted is AppResult.Failed) return admitted
        val roomId = (admitted as AppResult.Success).value
        val received = runtimes.readOriginalHistory(owner, deviceId, roomId, anchorEventId)
        // Detach bounded input before another repository can suspend or mutate its source.
        val snapshot =
            (received as? AppResult.Success)?.value?.let {
                if (it.changes.size > 128 || it.unresolvedEventIds.size > 128) null
                else
                    it.copy(
                        changes = it.changes.toList(),
                        unresolvedEventIds = it.unresolvedEventIds.toList(),
                    )
            }
        continuation(workspaceId, owner, received).let { if (it is AppResult.Failed) return it }
        if (received is AppResult.Failed) return received
        if (
            snapshot == null ||
                !validCollaborationOriginalWindow(snapshot, owner, deviceId, roomId, anchorEventId)
        )
            return failed("client.collaboration_invalid_history_window")
        val readmitted = admitRoom(workspaceId, owner, deviceId, conversationId)
        if (readmitted is AppResult.Failed) return readmitted
        if ((readmitted as AppResult.Success).value != roomId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        for (batch in snapshot.changes.chunked(16)) {
            val merged = history.merge(owner.localListAccess(), conversationId, roomId, batch)
            continuation(workspaceId, owner, merged).let { if (it is AppResult.Failed) return it }
            if (merged is AppResult.Failed) return merged
            if (!(merged as AppResult.Success).value)
                return failed("client.collaboration_history_changed")
        }
        return AppResult.Success(
            CollaborationHistoryAcquisition(snapshot.changes.size, snapshot.unresolvedEventIds.size)
        )
    }

    private suspend fun admitRoom(
        workspaceId: String,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
    ): AppResult<String> {
        val registered = checked(workspaceId, owner) { setup.read(owner) }
        if (registered is AppResult.Failed) return registered
        val device = (registered as AppResult.Success).value
        if (
            device?.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return failed("client.collaboration_device_unavailable", FailureKind.Unavailable)
        val read = checked(workspaceId, owner) { conversations.get(owner, conversationId) }
        if (read is AppResult.Failed) return read
        val direct = (read as AppResult.Success).value
        if (
            direct.id != conversationId ||
                direct.companyId != owner.companyId ||
                owner.accountId !in listOf(direct.firstAccountId, direct.secondAccountId)
        )
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val loaded = checked(workspaceId, owner) { conversations.room(owner, conversationId) }
        if (loaded is AppResult.Failed) return loaded
        val room = (loaded as AppResult.Success).value
        if (room.conversationId != conversationId)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        val id = room.matrixRoomId
        if (
            room.state != CollaborationDirectRoomState.READY ||
                id == null ||
                !id.startsWith('!') ||
                id.length !in 2..255 ||
                id.any { it.isWhitespace() || it.isISOControl() }
        )
            return failed("client.collaboration_room_not_ready", FailureKind.Unavailable)
        return AppResult.Success(id)
    }

    private suspend fun <T> checked(
        workspaceId: String,
        owner: CollaborationOwner,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val admitted = continuation(workspaceId, owner, result)
        return if (admitted is AppResult.Failed) admitted else result
    }

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        val rejected = (received as? AppResult.Failed)?.failure
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
            }
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.localListAccess())
        val current = capture(workspaceId)
        currentCoroutineContext().ensureActive()
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            return AppResult.Failed(rejected)
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val admitted = requireCollaborationOwner(current, owner, received)
        if (admitted is AppResult.Failed) return admitted
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private fun failed(code: String, kind: FailureKind = FailureKind.Conflict) =
        collaborationSetupFailure(code, kind)
}
