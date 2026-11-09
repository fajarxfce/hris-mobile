package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomBindingInventoryRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository

/**
 * Reads one raw item from the current retained seek. The caller must durably claim it before I/O.
 */
class LoadCollaborationSpaceRecoveryPage(
    private val inventory: CollaborationSpaceRoomBindingInventoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationSpaceRecoverySnapshot
    ): AppResult<CollaborationSpaceRoomBindingPage> {
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        val progress = snapshot.progress ?: return changedSpaceProgress()
        if (!validCollaborationSpaceRecoveryProgress(snapshot.owner, progress))
            return changedSpaceProgress(FailureKind.Validation)
        val sweep = progress.sweep ?: return changedSpaceProgress()
        if (sweep.pending != null || sweep.reachedEnd) return changedSpaceProgress()
        val page = inventory.page(snapshot.owner.localDirectoryAccess(), sweep.cursor, 1)
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        if (page is AppResult.Failed) return page
        val checked =
            nextCollaborationSpaceRecoverySweep(
                snapshot,
                CollaborationSpaceRecoveryChange.Attempt((page as AppResult.Success).value),
            )
        if (checked is AppResult.Failed) return checked
        return page
    }
}
