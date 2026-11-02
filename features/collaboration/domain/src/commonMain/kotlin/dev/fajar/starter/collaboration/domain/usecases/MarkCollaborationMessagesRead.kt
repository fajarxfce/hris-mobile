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

/** Explicit local read coverage and immutable metadata intent; no provider order or network I/O. */
class MarkCollaborationMessagesRead(
    private val states: CollaborationReadStateRepository,
    private val history: CollaborationHistoryRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    /** Retain the same intent ID across an uncertain local response. No unseen gaps are marked. */
    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
        intent: CollaborationReadReceiptIntent,
        expectedAccess: CollaborationLocalAccess? = null,
        /** Native callbacks may skip fully retained coverage after an old ACK is superseded. */
        onlyIfUnread: Boolean = false,
    ): AppResult<CollaborationReadState> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationReadSelection(conversationId, roomId, eventIds, false) ||
                !validCollaborationReadReceiptIntent(intent) ||
                intent.eventId !in eventIds
        )
            return invalidReadState()
        val selected = eventIds.toList()
        if (!execution.tryLock()) return unavailableReadState()
        return try {
            withTimeoutOrNull(10_000) {
                mark(scope, conversationId, roomId, selected, intent, expectedAccess, onlyIfUnread)
            } ?: unavailableReadState()
        } finally {
            execution.unlock()
        }
    }

    private suspend fun mark(
        scope: CollaborationLocalScope,
        conversationId: String,
        roomId: String,
        selected: List<String>,
        intent: CollaborationReadReceiptIntent,
        expectedAccess: CollaborationLocalAccess?,
        onlyIfUnread: Boolean,
    ): AppResult<CollaborationReadState> {
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (expectedAccess != null && access != expectedAccess)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        val conversation = lists.get(access, conversationId)
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (conversation is AppResult.Failed) return conversation
        if (
            !ownsCollaborationReadConversation(
                access,
                conversationId,
                (conversation as AppResult.Success).value,
            )
        )
            return unavailableReadState()
        for (eventId in selected) {
            val found = history.getEventFact(access, conversationId, roomId, eventId)
            continuation(scope, access).let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if (!validCollaborationReadFact((found as AppResult.Success).value, eventId))
                return unavailableReadState()
        }
        val loaded = load(scope, access, conversationId, roomId, selected)
        if (loaded is AppResult.Failed) return loaded
        val previous = (loaded as AppResult.Success).value
        val known =
            previous.pending.find { it.id == intent.id }
                ?: previous.acknowledged?.takeIf { it.id == intent.id }
        if (known != null && known != intent) return changedReadState()
        if (onlyIfUnread && selected.all { it in previous.readEventIds }) return loaded
        if (known == intent && selected.all { it in previous.readEventIds }) return loaded
        if (known == null && previous.pending.size >= 128)
            return collaborationSetupFailure("client.command_queue_full", FailureKind.Storage)
        val written =
            states.mark(access, conversationId, roomId, previous.version, selected, intent)
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        if (!(written as AppResult.Success).value) return changedReadState()
        val current = load(scope, access, conversationId, roomId, selected)
        if (current is AppResult.Failed) return current
        if (!selected.all { it in (current as AppResult.Success).value.readEventIds })
            return changedReadState()
        return current
    }

    private suspend fun load(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        selected: List<String>,
    ): AppResult<CollaborationReadState> {
        val loaded = states.read(access, conversationId, roomId, selected)
        val snapshot =
            (loaded as? AppResult.Success)?.value?.let {
                snapshotCollaborationReadState(it, selected)
            }
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        return snapshot?.let { AppResult.Success(it) } ?: changedReadState()
    }

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ) = sessions.checkReadStateContinuation(scope, access, lists, workspaces, server, build, clock)
}
