package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Admit one explicit global transition and its exact guard; no hidden read/retry loop. */
class ChangeCollaborationSpaceInboxHistoryProgress(
    private val progress: CollaborationSpaceInboxHistoryProgressRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistoryCheckpoint?,
        change: CollaborationSpaceInboxHistoryChange,
    ): AppResult<CollaborationSpaceInboxHistoryCheckpoint> {
        currentCoroutineContext().ensureActive()
        val proposed = collaborationSpaceInboxHistoryMutation(access, expected, change)
        if (proposed is AppResult.Failed) return proposed
        val mutation = (proposed as AppResult.Success).value
        admitted(access).let { if (it is AppResult.Failed) return it }
        val saved = progress.mutate(access, mutation)
        val captured =
            when (saved) {
                is AppResult.Failed -> saved
                is AppResult.Success ->
                    saved.value?.let { captureCollaborationSpaceInboxHistoryCheckpoint(it) }
                        ?: invalidGlobalHistoryWrite(FailureKind.Conflict)
            }
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (captured is AppResult.Failed) return captured
        val result = (captured as AppResult.Success).value
        val previous = mutation.expected
        val revision =
            when {
                previous == null -> 0L
                previous.progress == mutation.replacement -> previous.version.revision
                else -> previous.version.revision + 1
            }
        if (
            result.progress != mutation.replacement ||
                result.version.revision != revision ||
                previous != null && result.version.generation != previous.version.generation
        )
            return invalidGlobalHistoryWrite()
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
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalidGlobalHistoryWrite()
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

private fun invalidGlobalHistoryWrite(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_request"
            FailureKind.Conflict -> "client.collaboration_inbox_busy"
            else -> "client.collaboration_invalid_response"
        },
        kind,
    )
