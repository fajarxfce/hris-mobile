package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retain exact publication outcomes and pending cursor under one current admission. */
class RetainCollaborationSpaceInboxHistoryPending(
    private val progress: CollaborationSpaceInboxHistorySourceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        read: CollaborationSpaceInboxHistoryPendingPage,
        published: CollaborationSpaceInboxHistoryCommit,
    ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint> {
        currentCoroutineContext().ensureActive()
        val prepared =
            collaborationSpaceInboxHistoryPendingHandoff(access, expected, read, published)
        if (prepared is AppResult.Failed) return prepared
        val mutation = (prepared as AppResult.Success).value
        admitted(access).let { if (it is AppResult.Failed) return it }
        val saved = progress.mutate(access, mutation)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        val retained =
            (saved as AppResult.Success).value
                ?: return invalidPendingAdmission(FailureKind.Conflict)
        if (
            !validCollaborationSpaceInboxHistorySourceCheckpoint(access, retained) ||
                retained.version.generation != expected.version.generation ||
                retained.version.revision != expected.version.revision + 1 ||
                retained.progress != mutation.replacement
        )
            return invalidPendingAdmission()
        return AppResult.Success(retained)
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
