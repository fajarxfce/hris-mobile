package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.retainCollaborationCommandFailure
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSetupRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LoadCollaborationSetup(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val setup: CollaborationSetupRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(workspaceId: String): AppResult<CollaborationSetup> {
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val result = setup.read(owner)
        currentCoroutineContext().ensureActive()
        val admitted = requireCollaborationOwner(capture(workspaceId), owner)
        if (admitted is AppResult.Failed)
            return AppResult.Failed(
                retainCollaborationCommandFailure(
                    (result as? AppResult.Failed)?.failure,
                    admitted.failure,
                )
            )
        currentCoroutineContext().ensureActive()
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(CollaborationSetup(owner, result.value))
        }
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)
}
