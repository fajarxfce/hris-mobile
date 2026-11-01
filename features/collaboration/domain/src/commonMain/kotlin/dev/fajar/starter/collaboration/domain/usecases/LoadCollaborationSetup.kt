package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

class LoadCollaborationSetup(
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val setup: CollaborationSetupRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val replicas: CollaborationListRepository,
) {
    suspend operator fun invoke(workspaceId: String): AppResult<CollaborationSetup> {
        val captured = capture(workspaceId)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val access = owner.localListAccess()
        val initial = replicas.admission(access)
        if (initial is AppResult.Failed) return initial
        val gate = (initial as AppResult.Success).value
        var retainedDenial = gate.denial
        fun failed(failure: Failure) =
            AppResult.Failed(
                if (retainedDenial != null && !collaborationListResponseClosesRead(failure))
                    requireNotNull(retainedDenial)
                else failure
            )
        val before = requireCollaborationOwner(capture(workspaceId), owner, null)
        if (before is AppResult.Failed) return failed(before.failure)
        val result = setup.read(owner)
        if (result is AppResult.Failed && collaborationListResponseClosesRead(result.failure)) {
            retainedDenial = result.failure
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { replicas.restrict(access, result.failure) }
            }
        }
        currentCoroutineContext().ensureActive()
        val admitted = requireCollaborationOwner(capture(workspaceId), owner, result)
        if (admitted is AppResult.Failed) return failed(admitted.failure)
        if (result is AppResult.Failed) return failed(result.failure)
        val reopened = replicas.admit(access, gate.revision)
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(workspaceId), owner, reopened)
        if (current is AppResult.Failed) return failed(current.failure)
        if (reopened is AppResult.Failed) return failed(reopened.failure)
        if (!(reopened as AppResult.Success).value) {
            val latest = replicas.admission(access)
            currentCoroutineContext().ensureActive()
            val finalOwner = requireCollaborationOwner(capture(workspaceId), owner, latest)
            if (finalOwner is AppResult.Failed) return failed(finalOwner.failure)
            return when (latest) {
                is AppResult.Failed -> failed(latest.failure)
                is AppResult.Success ->
                    latest.value.denial?.let { AppResult.Failed(it) }
                        ?: failed(collaborationListConflict().failure)
            }
        }
        return AppResult.Success(CollaborationSetup(owner, (result as AppResult.Success).value))
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)
}
