package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** Finds every pending Space command, including conversations without a retained room binding. */
class ReadCollaborationSpacePendingMessages(
    private val messages: CollaborationSpaceMessageRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner
    ): AppResult<List<CollaborationSpaceMessageRecord>> =
        withTimeoutOrNull(5_000) {
            val admitted = admission(owner)
            if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
            val read = messages.observe(owner.localDirectoryAccess(), null).firstOrNull()
            // Detach mutable staging baselines before any subsequent ownership read can suspend.
            val captured =
                (read as? AppResult.Success)?.value?.let {
                    collaborationSpaceMessageInventory(owner.localDirectoryAccess(), it)
                }
            val current = admission(owner)
            if (current is AppResult.Failed) return@withTimeoutOrNull current
            if (read is AppResult.Failed) return@withTimeoutOrNull read
            if (captured == null)
                return@withTimeoutOrNull collaborationRecoveryFailure(
                    "client.collaboration_space_pending_inventory_changed",
                    FailureKind.Conflict,
                )
            AppResult.Success(
                captured.filter {
                    it.state !is CollaborationMessageState.Sent &&
                        it.state != CollaborationMessageState.Cancelled
                }
            )
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_space_pending_inventory_timeout",
                FailureKind.Timeout,
            )

    private suspend fun admission(owner: CollaborationSpaceOwner): AppResult<Unit> {
        val local = sessions.guardSpaceRecoveryOwner(server, owner)
        if (local is AppResult.Failed) return local
        val read = directory.admission(owner.localDirectoryAccess())
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val value = (read as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(value))
            return collaborationRecoveryFailure(
                "client.collaboration_space_pending_admission_changed",
                FailureKind.Conflict,
            )
        value.denial?.let {
            return AppResult.Failed(it)
        }
        return sessions.guardSpaceRecoveryOwner(server, owner)
    }
}
