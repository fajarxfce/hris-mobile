package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One bounded local reference page, independently of delivery and credential age. */
class ReadCollaborationSpaceInboxRepairs(
    private val repairs: CollaborationSpaceInboxRepairRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        limit: Int = 16,
    ): AppResult<CollaborationSpaceInboxRepairPage> {
        currentCoroutineContext().ensureActive()
        if (!(validCollaborationSpaceInboxRepairRequest(access, after, limit)))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val before = admitted(access)
        if (before is AppResult.Failed) return before
        val read = repairs.page(access, after, limit)
        val afterRead = admitted(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (read is AppResult.Failed) return read
        val value = (read as AppResult.Success).value
        if (!validCollaborationSpaceInboxRepairPage(access, after, limit, value))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        return AppResult.Success(
            when (value) {
                CollaborationSpaceInboxRepairPage.Restart -> value
                is CollaborationSpaceInboxRepairPage.Items ->
                    value.copy(references = value.references.toList())
            }
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
