package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryProgressRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ReadCollaborationRecoveryProgress(
    private val progress: CollaborationRecoveryProgressRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(): AppResult<CollaborationRecoverySnapshot> {
        val captured = sessions.captureRecoveryAccess(server)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        val result = progress.current(access)
        currentCoroutineContext().ensureActive()
        val guarded = sessions.guardRecoveryAccess(server, access)
        if (guarded is AppResult.Failed) return guarded
        if (result is AppResult.Failed) return result
        val value = (result as AppResult.Success).value
        if (value != null && !validCollaborationRecoveryProgress(value))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_recovery_progress",
                FailureKind.Unexpected,
            )
        return AppResult.Success(CollaborationRecoverySnapshot(access, value))
    }
}

internal suspend fun SessionRepository.captureRecoveryAccess(
    server: IdentityServer
): AppResult<CollaborationRecoveryAccess> {
    currentCoroutineContext().ensureActive()
    val result = current()
    currentCoroutineContext().ensureActive()
    return when (result) {
        is AppResult.Failed -> result
        is AppResult.Success -> collaborationRecoveryAccess(result.value, server.audience)
    }
}

internal suspend fun SessionRepository.guardRecoveryAccess(
    server: IdentityServer,
    expected: CollaborationRecoveryAccess,
): AppResult<Unit> {
    val current = captureRecoveryAccess(server)
    if (current is AppResult.Failed) return current
    if ((current as AppResult.Success).value != expected)
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(Unit)
}
