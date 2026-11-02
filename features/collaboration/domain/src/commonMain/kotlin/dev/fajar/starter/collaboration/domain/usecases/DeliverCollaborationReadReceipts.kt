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

/** One finite replay of exact retained intents. Never enrolls, joins, infers order or retries. */
class DeliverCollaborationReadReceipts(
    private val states: CollaborationReadStateRepository,
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
    private val execution = Mutex()

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
        roomId: String,
        limit: Int = 4,
    ): AppResult<CollaborationReadReceiptDelivery> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationReadSelection(conversationId, roomId, emptyList(), true) ||
                !validCollaborationMessageId(deviceId) ||
                limit !in 1..8 ||
                !collaborationOperationOwnsDevice(scope, deviceId)
        )
            return invalidReadState()
        if (!execution.tryLock()) return unavailableReadState()
        return try {
            withTimeoutOrNull(20_000) { deliver(scope, deviceId, conversationId, roomId, limit) }
                ?: unavailableReadState()
        } finally {
            execution.unlock()
        }
    }

    private suspend fun deliver(
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
        roomId: String,
        limit: Int,
    ): AppResult<CollaborationReadReceiptDelivery> {
        val captured = sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        continuation(scope, owner).let { if (it is AppResult.Failed) return it }
        val pending = load(scope, owner, conversationId, roomId)
        if (pending is AppResult.Failed) return pending
        val selected = (pending as AppResult.Success).value.pending.take(limit)
        var delivered = 0
        for (intent in selected) {
            val result = send(scope, owner, deviceId, conversationId, roomId, intent)
            if (result is AppResult.Failed) return result
            if ((result as AppResult.Success).value) delivered++
        }
        val current = load(scope, owner, conversationId, roomId)
        if (current is AppResult.Failed) return current
        return AppResult.Success(
            CollaborationReadReceiptDelivery(
                delivered,
                (current as AppResult.Success).value.pending.size,
            )
        )
    }

    private suspend fun send(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
        roomId: String,
        intent: CollaborationReadReceiptIntent,
    ): AppResult<Boolean> {
        val before = retained(scope, owner, conversationId, roomId, intent)
        if (before is AppResult.Failed || before == AppResult.Success(false)) return before
        val original =
            checked(scope, owner) {
                history.getEventFact(
                    owner.localListAccess(),
                    conversationId,
                    roomId,
                    intent.eventId,
                )
            }
        if (original is AppResult.Failed) return original
        if (!validCollaborationReadFact((original as AppResult.Success).value, intent.eventId))
            return changedReadState()
        admitRoom(scope, owner, deviceId, conversationId, roomId).let {
            if (it is AppResult.Failed) return it
        }
        // Admission may suspend; a removed intent must not begin a new metadata write afterward.
        val current = retained(scope, owner, conversationId, roomId, intent)
        if (current is AppResult.Failed || current == AppResult.Success(false)) return current
        val sent =
            checked(scope, owner, remote = true) {
                runtimes.sendReadReceipt(owner, deviceId, roomId, intent.eventId, intent.visibility)
            }
        if (sent is AppResult.Failed) return sent
        // A successful provider write does not authorize a late local ACK after revocation.
        admitRoom(scope, owner, deviceId, conversationId, roomId).let {
            if (it is AppResult.Failed) return it
        }
        val acknowledged =
            checked(scope, owner) {
                states.acknowledge(owner.localListAccess(), conversationId, roomId, intent)
            }
        if (acknowledged is AppResult.Failed) return acknowledged
        if (!(acknowledged as AppResult.Success).value) return changedReadState()
        return AppResult.Success(true)
    }

    private suspend fun retained(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
        roomId: String,
        intent: CollaborationReadReceiptIntent,
    ): AppResult<Boolean> {
        val read = load(scope, owner, conversationId, roomId, listOf(intent.eventId))
        if (read is AppResult.Failed) return read
        val state = (read as AppResult.Success).value
        val current = state.pending.find { it.id == intent.id } ?: return AppResult.Success(false)
        if (current != intent || intent.eventId !in state.readEventIds) return changedReadState()
        return AppResult.Success(true)
    }

    private suspend fun admitRoom(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        deviceId: String,
        conversationId: String,
        roomId: String,
    ): AppResult<Unit> {
        val registered = checked(scope, owner, remote = true) { setup.read(owner) }
        if (registered is AppResult.Failed) return registered
        val device = (registered as AppResult.Success).value
        val observedVersion =
            (scope as? CollaborationOperationScope.Background)
                ?.admission
                ?.observation
                ?.deviceVersion ?: 0
        if (
            device?.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.version < observedVersion ||
                device.platform.name != build.platform.name.uppercase()
        )
            return collaborationSetupFailure(
                "client.collaboration_device_unavailable",
                FailureKind.Unavailable,
            )
        val acquired =
            checked(scope, owner, remote = true) { conversations.get(owner, conversationId) }
        if (acquired is AppResult.Failed) return acquired
        val conversation = (acquired as AppResult.Success).value
        if (
            conversation.id != conversationId ||
                conversation.companyId != owner.companyId ||
                owner.accountId !in
                    listOf(conversation.firstAccountId, conversation.secondAccountId) ||
                conversation.firstAccountId == conversation.secondAccountId ||
                !validCollaborationMessageId(conversation.firstAccountId) ||
                !validCollaborationMessageId(conversation.secondAccountId)
        )
            return roomMismatch()
        val loaded =
            checked(scope, owner, remote = true) { conversations.room(owner, conversationId) }
        if (loaded is AppResult.Failed) return loaded
        val room = (loaded as AppResult.Success).value
        if (room.conversationId != conversationId || room.matrixRoomId != roomId)
            return roomMismatch()
        if (room.state != CollaborationDirectRoomState.READY) return unavailableReadState()
        return AppResult.Success(Unit)
    }

    private suspend fun load(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        conversationId: String,
        roomId: String,
        selected: List<String> = emptyList(),
    ): AppResult<CollaborationReadState> {
        val loaded = states.read(owner.localListAccess(), conversationId, roomId, selected)
        val snapshot =
            (loaded as? AppResult.Success)?.value?.let {
                snapshotCollaborationReadState(it, selected)
            }
        continuation(scope, owner, loaded).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        return snapshot?.let { AppResult.Success(it) } ?: changedReadState()
    }

    private suspend fun <T> checked(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        remote: Boolean = false,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val continued = continuation(scope, owner, result, if (remote) result else null)
        return if (continued is AppResult.Failed) continued else result
    }

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        context: AppResult<*>? = null,
        response: AppResult<*>? = null,
    ) =
        sessions.continueCollaborationControl(
            scope,
            owner,
            context,
            response,
            lists,
            workspaces,
            server,
            build,
            clock,
        )

    private fun roomMismatch() =
        collaborationSetupFailure(
            "client.collaboration_conversation_mismatch",
            FailureKind.AccessDenied,
        )
}
