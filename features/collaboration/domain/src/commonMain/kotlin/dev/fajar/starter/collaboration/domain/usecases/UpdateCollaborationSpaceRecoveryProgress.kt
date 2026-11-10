package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecoveryProgressRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class UpdateCollaborationSpaceRecoveryProgress(
    private val progress: CollaborationSpaceRecoveryProgressRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationSpaceRecoverySnapshot,
        change: CollaborationSpaceRecoveryChange,
    ): AppResult<CollaborationSpaceRecoveryUpdate> {
        currentCoroutineContext().ensureActive()
        // Only the dedicated Finish owner rechecks both complete local inventories.
        if (change == CollaborationSpaceRecoveryChange.Finish)
            return changedSpaceProgress(FailureKind.Validation)
        val next = nextCollaborationSpaceRecoverySweep(snapshot, change)
        if (next is AppResult.Failed) return next
        val sweep = (next as AppResult.Success).value
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        val result = progress.replace(snapshot.owner, snapshot.progress, sweep)
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        if (result is AppResult.Failed) return result
        val value = (result as AppResult.Success).value ?: return changedSpaceProgress()
        if (!validCollaborationSpaceRecoveryProgress(snapshot.owner, value) || value.sweep != sweep)
            return changedSpaceProgress(FailureKind.Unexpected)
        return AppResult.Success(
            CollaborationSpaceRecoveryUpdate(snapshot.copy(progress = value), null)
        )
    }
}
