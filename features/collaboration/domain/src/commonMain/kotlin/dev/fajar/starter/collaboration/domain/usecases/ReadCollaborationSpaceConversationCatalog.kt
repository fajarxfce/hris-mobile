package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One finite local inventory read. Admission and catalog progress never depend on token age. */
class ReadCollaborationSpaceConversationCatalog(
    private val catalog: CollaborationSpaceConversationCatalogRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        after: String? = null,
        rawLimit: Int = 50,
    ): AppResult<CollaborationSpaceConversationCatalogRead> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceCatalogRequest(access, after, rawLimit))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val before = admitted(access)
        if (before is AppResult.Failed) return before
        val read = catalog.read(access, after, rawLimit)
        val afterRead = admitted(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (read is AppResult.Failed) return read
        val value = (read as AppResult.Success).value
        if (!validCollaborationSpaceCatalogRead(access, after, rawLimit, value))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        return AppResult.Success(
            when (value) {
                CollaborationSpaceConversationCatalogRead.Restart -> value
                is CollaborationSpaceConversationCatalogRead.Page ->
                    value.copy(items = value.items.toList())
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
