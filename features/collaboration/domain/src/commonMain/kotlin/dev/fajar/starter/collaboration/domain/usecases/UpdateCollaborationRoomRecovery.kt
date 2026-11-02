package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The processing owner persists each transition; this never admits SDK or acknowledges content. */
class UpdateCollaborationRoomRecovery(
    private val progress: CollaborationRoomRecoveryRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationRoomRecoverySnapshot,
        change: CollaborationRoomRecoveryChange,
    ): AppResult<CollaborationRoomRecoveryUpdate> {
        currentCoroutineContext().ensureActive()
        val next = nextCollaborationRoomRecoveryPosition(snapshot.progress, change)
        if (next is AppResult.Failed) return next
        sessions.guardRoomRecovery(snapshot.access, lists, server).let {
            if (it is AppResult.Failed) return it
        }
        val position = (next as AppResult.Success).value
        val saved = progress.replace(snapshot.access, snapshot.progress, position)
        sessions.guardRoomRecovery(snapshot.access, lists, server).let {
            if (it is AppResult.Failed) return it
        }
        if (saved is AppResult.Failed) return saved
        val value =
            (saved as AppResult.Success).value
                ?: return collaborationSetupFailure(
                    "client.collaboration_room_recovery_changed",
                    FailureKind.Conflict,
                )
        if (
            !validCollaborationRoomRecoveryProgress(value) ||
                value.position != position ||
                value.generation == snapshot.progress?.generation
        )
            return invalidRoomRecovery()
        return AppResult.Success(
            CollaborationRoomRecoveryUpdate(
                snapshot.copy(progress = value),
                if (change == CollaborationRoomRecoveryChange.Finish)
                    collaborationRoomRecoveryCompletion(snapshot.progress)
                else null,
            )
        )
    }
}
