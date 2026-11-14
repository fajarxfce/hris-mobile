package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One admitted bounded pending interval, independent of raw history and transport. */
class ReadCollaborationSpaceInboxHistoryPending(
    private val progress: CollaborationSpaceInboxHistorySourceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        limit: Int = 16,
    ): AppResult<CollaborationSpaceInboxHistoryPendingPage> {
        currentCoroutineContext().ensureActive()
        if (
            limit !in 1..16 ||
                !validCollaborationSpaceInboxHistorySourceCheckpoint(access, expected)
        )
            return invalidPendingAdmission(FailureKind.Validation)
        admitted(access).let { if (it is AppResult.Failed) return it }
        val read =
            progress.pending(access, expected.progress.conversationId, expected.version, limit)
        val captured =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success ->
                    read.value?.let {
                        if (it.references.size > limit) invalidPendingAdmission()
                        else AppResult.Success(it.copy(references = it.references.toList()))
                    } ?: invalidPendingAdmission(FailureKind.Conflict)
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (captured is AppResult.Failed) return captured
        val page = (captured as AppResult.Success).value
        if (!validCollaborationSpaceInboxHistoryPendingPage(access, expected, limit, page))
            return invalidPendingAdmission()
        return AppResult.Success(page)
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
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidPendingAdmission()
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

private fun invalidPendingAdmission(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
