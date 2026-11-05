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
import kotlinx.coroutines.flow.first

/** Ephemeral local validation shared by preparation and explicit publication. No credentials. */
internal class CollaborationForwardAccess(
    private val workspaceId: String,
    private val expected: CollaborationLocalAccess,
    private val quotes: CollaborationQuoteRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend fun guard(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(expected)
        val current = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    suspend fun verify(selection: CollaborationForwardSelection): AppResult<Unit> {
        guard().let { if (it is AppResult.Failed) return it }
        for (id in (listOf(selection.conversationId) + selection.destinationIds).distinct()) {
            val read = lists.get(expected, id)
            guard().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            if (!ownsCollaborationReadConversation(expected, id, (read as AppResult.Success).value))
                return forwardUnavailable()
        }
        // One indexed, coherent visible snapshot. Hidden/redacted originals are omitted by the
        // repository, including changes outside the currently displayed conversation page.
        val selected =
            quotes
                .observeQuotes(
                    expected,
                    selection.conversationId,
                    selection.roomId,
                    selection.messages.map { it.reference.eventId },
                )
                .first()
        guard().let { if (it is AppResult.Failed) return it }
        if (selected is AppResult.Failed) return selected
        val visible = (selected as AppResult.Success).value
        if (
            visible.size != selection.messages.size ||
                visible.map { it.eventId }.distinct().size != visible.size
        )
            return forwardUnavailable()
        for (source in selection.messages) {
            val message = visible.singleOrNull { it.eventId == source.reference.eventId }
            if (message?.sender != source.reference.sender || message.text != source.text)
                return forwardUnavailable()
        }
        return AppResult.Success(Unit)
    }
}

internal fun forwardUnavailable(kind: FailureKind = FailureKind.Conflict) =
    collaborationSetupFailure("client.collaboration_forward_changed", kind)
