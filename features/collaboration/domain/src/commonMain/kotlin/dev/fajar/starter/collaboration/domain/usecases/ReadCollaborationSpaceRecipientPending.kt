package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** One admitted, bounded source page. Discovery and transfer remain separate operations. */
class ReadCollaborationSpaceRecipientPending(
    private val outbox: CollaborationSpaceRecipientOutboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 8,
    ): AppResult<CollaborationSpaceRecipientSubmissionPage> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceRecipientPendingRequest(access, after, limit))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return withTimeoutOrNull(5_000) {
            admitted(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val read = outbox.pending(access, after, limit)
            val captured =
                when (read) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        captureCollaborationSpaceRecipientPending(access, after, limit, read.value)
                }
            admitted(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            captured
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_composer_recovery_timeout",
                FailureKind.Timeout,
            )
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
