package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inspect exact retained progress before resuming an older unfinished pass. */
class ReadCollaborationSpaceInboxHistorySource(
    private val progress: CollaborationSpaceInboxHistorySourceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceAccountAccess(
                CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
            ) ||
                !validCollaborationSpaceConversationId(access.spaceId) ||
                !validCollaborationMessageId(conversationId)
        )
            return invalidSourceRecovery(FailureKind.Validation)
        admitted(access).let { if (it is AppResult.Failed) return it }
        val read = progress.current(access, conversationId)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val value = (read as AppResult.Success).value ?: return AppResult.Success(null)
        if (
            !validCollaborationSpaceInboxHistorySourceCheckpoint(access, value) ||
                value.progress.conversationId != conversationId
        )
            return invalidSourceRecovery()
        return AppResult.Success(value)
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
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidSourceRecovery()
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

private fun invalidSourceRecovery(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
