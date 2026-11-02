package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryProgressRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The single application processing owner advances checkpoints; this never schedules or admits I/O.
 */
class UpdateCollaborationRecoveryProgress(
    private val progress: CollaborationRecoveryProgressRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationRecoverySnapshot,
        change: CollaborationRecoveryChange,
    ): AppResult<CollaborationRecoveryUpdate> {
        currentCoroutineContext().ensureActive()
        val next = nextCollaborationRecoverySweep(snapshot.progress, change)
        if (next is AppResult.Failed) return next
        val sweep = (next as AppResult.Success).value
        val before = sessions.guardRecoveryAccess(server, snapshot.access)
        if (before is AppResult.Failed) return before
        val saved = progress.replace(snapshot.access, snapshot.progress, sweep)
        currentCoroutineContext().ensureActive()
        val after = sessions.guardRecoveryAccess(server, snapshot.access)
        if (after is AppResult.Failed) return after
        if (saved is AppResult.Failed) return saved
        val value =
            (saved as AppResult.Success).value
                ?: return collaborationRecoveryFailure(
                    "client.collaboration_recovery_progress_changed",
                    FailureKind.Conflict,
                )
        if (!validCollaborationRecoveryProgress(value) || value.sweep != sweep)
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_recovery_progress",
                FailureKind.Unexpected,
            )
        val completed =
            if (change == CollaborationRecoveryChange.Finish && sweep == null)
                CollaborationRecoveryWake(requireNotNull(snapshot.progress?.sweep).wakeGeneration)
            else null
        return AppResult.Success(
            CollaborationRecoveryUpdate(snapshot.copy(progress = value), completed)
        )
    }
}
