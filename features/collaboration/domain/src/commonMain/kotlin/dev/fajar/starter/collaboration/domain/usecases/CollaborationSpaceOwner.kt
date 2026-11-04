package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun SessionRepository.captureCollaborationSpaceParent(
    expected: CollaborationNativeParent,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Session> {
    val loaded = current()
    currentCoroutineContext().ensureActive()
    if (loaded is AppResult.Failed) return loaded
    val session = (loaded as AppResult.Success).value
    val parent = collaborationNativeParent(session, server, build, clock.now())
    if (parent is AppResult.Failed) return parent
    if ((parent as AppResult.Success).value != expected)
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(requireNotNull(session))
}
