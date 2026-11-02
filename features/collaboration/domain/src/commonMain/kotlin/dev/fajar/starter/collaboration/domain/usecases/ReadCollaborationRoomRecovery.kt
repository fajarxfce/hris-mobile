package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owned room progress is independent of the selected workspace and live credential age. */
class ReadCollaborationRoomRecovery(
    private val progress: CollaborationRoomRecoveryRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationLocalAccess
    ): AppResult<CollaborationRoomRecoverySnapshot> {
        sessions.guardRoomRecovery(access, lists, server).let {
            if (it is AppResult.Failed) return it
        }
        val read = progress.current(access)
        sessions.guardRoomRecovery(access, lists, server).let {
            if (it is AppResult.Failed) return it
        }
        if (read is AppResult.Failed) return read
        val value = (read as AppResult.Success).value
        if (value != null && !validCollaborationRoomRecoveryProgress(value))
            return invalidRoomRecovery()
        return AppResult.Success(CollaborationRoomRecoverySnapshot(access, value))
    }
}

internal suspend fun SessionRepository.guardRoomRecovery(
    access: CollaborationLocalAccess,
    lists: CollaborationListRepository,
    server: IdentityServer,
): AppResult<Unit> {
    currentCoroutineContext().ensureActive()
    val gate = lists.admission(access)
    val current = current()
    currentCoroutineContext().ensureActive()
    if (gate is AppResult.Success)
        gate.value.denial?.let {
            return AppResult.Failed(it)
        }
    if (current is AppResult.Failed) return current
    val owner =
        collaborationCapturedLocalAccess(
            access,
            (current as AppResult.Success).value,
            server.audience,
        )
    if (owner is AppResult.Failed) return owner
    if (gate is AppResult.Failed) return gate
    return AppResult.Success(Unit)
}

internal fun invalidRoomRecovery(kind: FailureKind = FailureKind.Storage) =
    collaborationSetupFailure("client.collaboration_invalid_room_recovery", kind)
