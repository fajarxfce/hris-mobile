package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationPushPriorityRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ReadCollaborationPushPriority(
    private val priorities: CollaborationPushPriorityRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(): AppResult<CollaborationPushSnapshot> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val result = priorities.current(parent)
        currentCoroutineContext().ensureActive()
        val guarded = sessions.guardPushParent(server, build, parent)
        if (guarded is AppResult.Failed) return guarded
        if (result is AppResult.Failed) return result
        val value = (result as AppResult.Success).value
        if (value != null && !validCollaborationPushPriority(value))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_push_priority",
                FailureKind.Unexpected,
            )
        return AppResult.Success(CollaborationPushSnapshot(parent, value))
    }
}

internal suspend fun SessionRepository.capturePushParent(
    server: IdentityServer,
    build: AppBuild,
): AppResult<CollaborationNativeParent> {
    currentCoroutineContext().ensureActive()
    val result = current()
    currentCoroutineContext().ensureActive()
    return when (result) {
        is AppResult.Failed -> result
        is AppResult.Success -> collaborationPushParent(result.value, server, build)
    }
}

internal suspend fun SessionRepository.guardPushParent(
    server: IdentityServer,
    build: AppBuild,
    expected: CollaborationNativeParent,
): AppResult<Unit> {
    val result = capturePushParent(server, build)
    if (result is AppResult.Failed) return result
    if ((result as AppResult.Success).value != expected)
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(Unit)
}
