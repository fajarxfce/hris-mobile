package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository

/** Rechecks the current local inventory before completing the captured finite sweep. */
class FinishCollaborationSpaceRecovery(
    private val progress: CollaborationSpaceRecoveryProgressRepository,
    private val inventory: CollaborationSpaceRoomBindingInventoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationSpaceRecoverySnapshot
    ): AppResult<CollaborationSpaceRecoveryUpdate> {
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        val proposed =
            nextCollaborationSpaceRecoverySweep(snapshot, CollaborationSpaceRecoveryChange.Finish)
        if (proposed is AppResult.Failed) return proposed
        val previous = requireNotNull(snapshot.progress)
        val swept = requireNotNull(previous.sweep)
        val fresh = inventory.page(snapshot.owner.localDirectoryAccess(), null, 1)
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        if (fresh is AppResult.Failed) return fresh
        val page = (fresh as AppResult.Success).value
        val first = CollaborationSpaceRecoverySweep(swept.wakeGeneration)
        val checked =
            nextCollaborationSpaceRecoverySweep(
                snapshot.copy(progress = previous.copy(sweep = first)),
                CollaborationSpaceRecoveryChange.Attempt(page),
            )
        if (checked is AppResult.Failed) return checked
        val old = swept.boundary
        val latest = page.boundary
        if (
            old != null &&
                (latest == null ||
                    latest.generation != old.generation ||
                    latest.revision < old.revision)
        )
            return changedSpaceProgress()
        val next =
            if (latest != old || page.hasNewerBindings) first
            else (proposed as AppResult.Success).value
        val result = progress.replace(snapshot.owner, previous, next)
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        if (result is AppResult.Failed) return result
        val saved = (result as AppResult.Success).value ?: return changedSpaceProgress()
        if (!validCollaborationSpaceRecoveryProgress(snapshot.owner, saved) || saved.sweep != next)
            return changedSpaceProgress(FailureKind.Unexpected)
        return AppResult.Success(
            CollaborationSpaceRecoveryUpdate(
                snapshot.copy(progress = saved),
                if (next == null) CollaborationSpaceRecoveryWake(swept.wakeGeneration) else null,
            )
        )
    }
}
