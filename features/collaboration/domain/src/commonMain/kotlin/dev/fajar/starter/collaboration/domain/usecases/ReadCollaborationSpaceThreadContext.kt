package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Fresh local context for an explicit thread action; no credential renewal or provider work. */
class ReadCollaborationSpaceThreadContext(
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationSpaceConversationMetadata> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceConversationId(conversationId))
            return collaborationRecoveryFailure(
                "client.collaboration_conversation_unavailable",
                FailureKind.Validation,
            )
        return withTimeoutOrNull(5_000) {
            admitted(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val captured = metadata.getById(access, conversationId)
            admitted(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val value = (captured as AppResult.Success).value
            if (
                value == null ||
                    value.id != conversationId ||
                    !validCollaborationSpaceConversationMetadata(access, value)
            )
                collaborationRecoveryFailure(
                    "client.collaboration_conversation_unavailable",
                    FailureKind.Unavailable,
                )
            else AppResult.Success(value)
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_recovery_timeout",
                FailureKind.Timeout,
            )
    }

    /** Read admission between two current-session checks; a confirmed denial cannot be masked. */
    private suspend fun admitted(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val first = sessions.current()
        currentCoroutineContext().ensureActive()
        if (first is AppResult.Failed) return first
        val owned =
            localCollaborationSpaceAccess(
                access,
                (first as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        val read = directory.admission(access)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val gate = (read as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(gate))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
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
