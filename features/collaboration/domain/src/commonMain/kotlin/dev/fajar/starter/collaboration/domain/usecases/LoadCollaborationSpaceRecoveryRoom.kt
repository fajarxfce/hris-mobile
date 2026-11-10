package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository

/** Resolves an already claimed room without advancing or acknowledging it. */
class LoadCollaborationSpaceRecoveryRoom(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val inventory: CollaborationSpaceRoomBindingInventoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationSpaceRecoverySnapshot
    ): AppResult<CollaborationSpaceConversation?> {
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        val progress = snapshot.progress ?: return changedSpaceProgress()
        if (!validCollaborationSpaceRecoveryProgress(snapshot.owner, progress))
            return changedSpaceProgress(FailureKind.Validation)
        val sweep = progress.sweep ?: return changedSpaceProgress()
        val claim = sweep.pending ?: return changedSpaceProgress()
        val id = claim.conversationId ?: return AppResult.Success(null)
        val access = snapshot.owner.localDirectoryAccess()
        val result = bindings.getByConversation(access, id)
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        if (result is AppResult.Failed) return result
        val binding = (result as AppResult.Success).value ?: return changedSpaceProgress()
        if (!validCollaborationSpaceRoomBinding(access, binding) || binding.conversation.id != id)
            return changedSpaceProgress()
        // Recheck the generation after the exact lookup. Concurrent appends are allowed; replacing
        // the inventory namespace cannot silently turn an old pending claim into new work.
        val boundary = inventory.page(access, sweep.cursor, 1)
        sessions.guardSpaceRecoveryOwner(server, snapshot.owner).let {
            if (it is AppResult.Failed) return it
        }
        if (boundary is AppResult.Failed) return boundary
        val current =
            (boundary as AppResult.Success).value.boundary ?: return changedSpaceProgress()
        val expected = sweep.boundary ?: return changedSpaceProgress()
        if (
            current.audience != expected.audience ||
                current.accountId != expected.accountId ||
                current.spaceId != expected.spaceId ||
                current.generation != expected.generation ||
                current.revision < expected.revision
        )
            return changedSpaceProgress()
        val c = binding.conversation
        return AppResult.Success(
            CollaborationSpaceConversation(
                snapshot.owner,
                c.id,
                c.firstAccountId,
                c.secondAccountId,
                c.createdBy,
                c.createdAt,
                c.version,
            )
        )
    }
}
