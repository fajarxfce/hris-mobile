package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryProgressRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ReadCollaborationSpaceRecoveryProgress(
    private val progress: CollaborationSpaceRecoveryProgressRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceRecoverySnapshot> {
        sessions.guardSpaceRecoveryOwner(server, owner).let {
            if (it is AppResult.Failed) return it
        }
        val result = progress.current(owner)
        sessions.guardSpaceRecoveryOwner(server, owner).let {
            if (it is AppResult.Failed) return it
        }
        if (result is AppResult.Failed) return result
        val value = (result as AppResult.Success).value
        if (value != null && !validCollaborationSpaceRecoveryProgress(owner, value))
            return changedSpaceProgress(FailureKind.Unexpected)
        return AppResult.Success(CollaborationSpaceRecoverySnapshot(owner, value))
    }
}

/** Local family ownership only; expired transport credentials do not hide retained progress. */
internal suspend fun SessionRepository.guardSpaceRecoveryOwner(
    server: IdentityServer,
    owner: CollaborationSpaceOwner,
): AppResult<Unit> {
    currentCoroutineContext().ensureActive()
    if (!validCollaborationSpaceRecoveryOwner(owner) || owner.parent.audience != server.audience)
        return changedSpaceProgress(FailureKind.Unauthorized)
    val result = current()
    currentCoroutineContext().ensureActive()
    if (result is AppResult.Failed) return result
    val session =
        (result as AppResult.Success).value ?: return changedSpaceProgress(FailureKind.Unauthorized)
    if (
        session.id != owner.parent.sessionId ||
            session.user.id != owner.parent.accountId ||
            session.binding.audience != owner.parent.audience ||
            session.binding.serverId != owner.parent.nativeSessionId
    )
        return changedSpaceProgress(FailureKind.Unauthorized)
    return AppResult.Success(Unit)
}

internal fun changedSpaceProgress(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_space_recovery_progress_changed", kind)
