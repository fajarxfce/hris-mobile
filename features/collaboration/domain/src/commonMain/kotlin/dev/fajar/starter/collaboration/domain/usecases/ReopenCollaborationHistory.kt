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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Explicit capacity recovery. Each close/restore boundary is admitted; no implicit history retry.
 */
class ReopenCollaborationHistory(
    private val runtimes: CollaborationRuntimeRepository,
    private val lists: CollaborationListRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
        anchorEventId: String? = null,
    ): AppResult<CollaborationRead<CollaborationHistoryRuntimePreparation>> =
        execution.withLock {
            if (
                !validCollaborationMessageId(deviceId) ||
                    !validCollaborationMessageId(conversationId) ||
                    (anchorEventId != null && !validCollaborationEventId(anchorEventId))
            )
                return@withLock failed(
                    "client.collaboration_invalid_history",
                    FailureKind.Validation,
                )
            val captured = capture(workspaceId)
            if (captured is AppResult.Failed) return@withLock captured
            val owner = (captured as AppResult.Success).value
            continuation(workspaceId, owner, null).let {
                if (it is AppResult.Failed) return@withLock it
            }
            val admitted = admitRoom(workspaceId, owner, deviceId, conversationId)
            if (admitted is AppResult.Failed) return@withLock admitted
            val room = (admitted as AppResult.Success).value
            val selected =
                selectAnchor(workspaceId, owner, deviceId, conversationId, room, anchorEventId)
            if (selected is AppResult.Failed) return@withLock selected
            val anchor =
                (selected as AppResult.Success).value
                    ?: return@withLock AppResult.Success(
                        CollaborationRead(owner, CollaborationHistoryRuntimePreparation.Available)
                    )
            val prepared =
                checked(workspaceId, owner) {
                    runtimes.prepareHistoryReopen(owner, deviceId, room, anchor)
                }
            if (prepared is AppResult.Failed) return@withLock prepared
            if (!(prepared as AppResult.Success).value)
                return@withLock AppResult.Success(
                    CollaborationRead(owner, CollaborationHistoryRuntimePreparation.Available)
                )
            val ready = admitRoom(workspaceId, owner, deviceId, conversationId)
            if (ready is AppResult.Failed) return@withLock ready
            if ((ready as AppResult.Success).value != room)
                return@withLock failed(
                    "client.collaboration_conversation_mismatch",
                    FailureKind.AccessDenied,
                )
            restorePrepared(workspaceId, owner, deviceId, conversationId, room)
        }

    private suspend fun selectAnchor(
        workspaceId: String,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
        room: String,
        requested: String?,
    ): AppResult<String?> {
        if (requested != null) return AppResult.Success(requested)
        val read = checked(workspaceId, owner) { runtimes.findHistoryHead(owner, deviceId, room) }
        if (read is AppResult.Failed) return read
        val head = (read as AppResult.Success).value
        if (
            head.owner != owner ||
                head.deviceId != deviceId ||
                head.roomId != room ||
                (head.eventId != null && !validCollaborationEventId(head.eventId))
        )
            return failed("client.collaboration_invalid_history_head")
        val admitted = admitRoom(workspaceId, owner, deviceId, conversationId)
        if (admitted is AppResult.Failed) return admitted
        if ((admitted as AppResult.Success).value != room)
            return failed("client.collaboration_conversation_mismatch", FailureKind.AccessDenied)
        // An absent head never clears history, closes a runtime, or acknowledges a message.
        return AppResult.Success(head.eventId)
    }

    private suspend fun restorePrepared(
        workspaceId: String,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
        room: String,
    ): AppResult<CollaborationRead<CollaborationHistoryRuntimePreparation>> {
        var opened: CollaborationRuntimeLease? = null
        var retained = false
        try {
            val restored = runtimes.restore(owner, deviceId)
            if (restored is AppResult.Success) opened = restored.value
            continuation(workspaceId, owner, restored).let { if (it is AppResult.Failed) return it }
            if (restored is AppResult.Failed) return restored
            val lease =
                opened
                    ?: return AppResult.Success(
                        CollaborationRead(
                            owner,
                            CollaborationHistoryRuntimePreparation.SetupRequired,
                        )
                    )
            if (lease.owner != owner || lease.deviceId != deviceId)
                return failed("client.session_changed", FailureKind.Unauthorized)
            lease.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            val finished = admitRoom(workspaceId, owner, deviceId, conversationId)
            if (finished is AppResult.Failed) return finished
            if ((finished as AppResult.Success).value != room)
                return failed(
                    "client.collaboration_conversation_mismatch",
                    FailureKind.AccessDenied,
                )
            lease.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            currentCoroutineContext().ensureActive()
            retained = true
            return AppResult.Success(
                CollaborationRead(owner, CollaborationHistoryRuntimePreparation.Reopened)
            )
        } finally {
            if (!retained) withContext(NonCancellable) { opened?.close() }
        }
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
