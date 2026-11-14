package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Admitted local lifecycle preparation; retained unresolved references are never discarded. */
class PrepareCollaborationSpaceInboxHistorySource(
    private val progress: CollaborationSpaceInboxHistorySourceRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        passId: String,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceAccountAccess(
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            ) ||
                !validCollaborationSpaceConversationId(access.spaceId) ||
                !validCollaborationMessageId(conversationId) ||
                !validCollaborationMessageId(passId)
        )
            return invalidHistoryPreparation(FailureKind.Validation)
        admitted(access).let { if (it is AppResult.Failed) return it }
        val bound = bindings.getByConversation(access, conversationId)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (bound is AppResult.Failed) return bound
        val location =
            resolveCollaborationSpaceHistoryLocation(
                access,
                conversationId,
                (bound as AppResult.Success).value,
            )
        if (location is AppResult.Failed) return location
        val owner = collaborationHistoryChangeOwner((location as AppResult.Success).value)
        val index = inbox.conversation(access, conversationId, 2)
        val captured =
            when (index) {
                is AppResult.Failed -> index
                is AppResult.Success ->
                    captureCollaborationSpaceInboxConversation(conversationId, index.value)
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (captured is AppResult.Failed) return captured
        val destination = (captured as AppResult.Success).value.version
        val read = progress.current(access, conversationId)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val current = (read as AppResult.Success).value
        val prepared =
            prepareCollaborationSpaceInboxHistorySource(
                access,
                conversationId,
                owner,
                passId,
                destination,
                current,
            )
        if (prepared is AppResult.Failed) return prepared
        val replacement = (prepared as AppResult.Success).value
        if (current != null && current.progress == replacement) return AppResult.Success(current)
        val saved =
            progress.mutate(
                access,
                CollaborationSpaceInboxHistorySourceMutation(
                    current,
                    replacement,
                    destination,
                    emptyList(),
                    emptyList(),
                ),
            )
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        val result =
            (saved as AppResult.Success).value
                ?: return invalidHistoryPreparation(FailureKind.Conflict)
        if (
            !validCollaborationSpaceInboxHistorySourceCheckpoint(access, result) ||
                result.progress != replacement ||
                result.version.revision != (current?.version?.revision?.plus(1) ?: 0L) ||
                (current != null && result.version.generation != current.version.generation)
        )
            return invalidHistoryPreparation()
        return AppResult.Success(result)
    }

    private suspend fun admitted(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val owned =
            localCollaborationSpaceAccess(
                access,
                (identity as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        val read = directory.admission(access)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val gate = (read as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidHistoryPreparation()
        gate.denial?.let {
            return AppResult.Failed(it)
        }
        val latest = sessions.current()
        currentCoroutineContext().ensureActive()
        if (latest is AppResult.Failed) return latest
        val current =
            localCollaborationSpaceAccess(
                access,
                (latest as AppResult.Success).value,
                server.audience,
            )
        return if (current is AppResult.Failed) current else AppResult.Success(Unit)
    }
}

private fun invalidHistoryPreparation(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
