package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Local wake-up only. The worker separately re-admits live access and reads encrypted intent. */
class RequestCollaborationDelivery(
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(workspaceId: String): AppResult<Unit> {
        val captured = capture(workspaceId)
        currentCoroutineContext().ensureActive()
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        val scheduled =
            withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        return scheduled
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Timeout,
                    "Could not request chat delivery.",
                    code = "client.collaboration_schedule_timeout",
                )
            )
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)

    private suspend fun guard(
        workspaceId: String,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val admission = lists.admission(access)
        val current = capture(workspaceId)
        currentCoroutineContext().ensureActive()
        if (admission is AppResult.Success)
            admission.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != access)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (admission is AppResult.Failed) return admission
        return AppResult.Success(Unit)
    }
}
