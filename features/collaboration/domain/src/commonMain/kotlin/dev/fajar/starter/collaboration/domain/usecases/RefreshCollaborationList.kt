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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One explicit live page. The shared instance serializes mutable labels; missing rows are retained.
 */
class RefreshCollaborationList(
    private val replicas: CollaborationListRepository,
    private val conversations: CollaborationConversationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val acquisition = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        after: String? = null,
        limit: Int = 30,
    ): AppResult<CollaborationRead<CollaborationPage<CollaborationDirectSummary>>> =
        acquisition.withLock {
            val owner =
                when (val captured = capture(workspaceId)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            val access = owner.localListAccess()
            val admission =
                when (val current = replicas.admission(access)) {
                    is AppResult.Failed -> return@withLock current
                    is AppResult.Success -> current.value
                }
            var retainedDenial = admission.denial
            fun failed(failure: Failure): AppResult.Failed =
                AppResult.Failed(
                    if (
                        retainedDenial?.let(::collaborationListResponseClosesRead) == true &&
                            !collaborationListResponseClosesRead(failure)
                    )
                        requireNotNull(retainedDenial)
                    else failure
                )
            val checkpoint =
                when (val current = replicas.checkpoint(access)) {
                    is AppResult.Failed -> return@withLock failed(current.failure)
                    is AppResult.Success -> current.value
                }
            val before = requireCollaborationOwner(capture(workspaceId), owner, null)
            if (before is AppResult.Failed) return@withLock failed(before.failure)
            val received = conversations.summaries(owner, after, limit)
            // A received denial must reach the process gate even if the route is cancelled now.
            if (
                received is AppResult.Failed &&
                    collaborationListResponseClosesRead(received.failure)
            ) {
                retainedDenial = received.failure
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { replicas.restrict(access, received.failure) }
                }
            }
            currentCoroutineContext().ensureActive()
            val admitted = requireCollaborationOwner(capture(workspaceId), owner, received)
            if (admitted is AppResult.Failed) return@withLock failed(admitted.failure)
            if (received is AppResult.Failed) return@withLock failed(received.failure)
            val page = (received as AppResult.Success).value
            val merged = replicas.merge(access, checkpoint, page.items, clock.now())
            currentCoroutineContext().ensureActive()
            val afterWrite = requireCollaborationOwner(capture(workspaceId), owner, merged)
            if (afterWrite is AppResult.Failed) return@withLock failed(afterWrite.failure)
            when (merged) {
                is AppResult.Failed -> return@withLock failed(merged.failure)
                is AppResult.Success ->
                    if (!merged.value) return@withLock failed(collaborationListConflict().failure)
            }
            val reopened = replicas.admit(access, admission.revision)
            currentCoroutineContext().ensureActive()
            val finalOwner = requireCollaborationOwner(capture(workspaceId), owner, reopened)
            if (finalOwner is AppResult.Failed) return@withLock failed(finalOwner.failure)
            when (reopened) {
                is AppResult.Failed -> failed(reopened.failure)
                is AppResult.Success ->
                    if (reopened.value) AppResult.Success(CollaborationRead(owner, page))
                    else {
                        val latest = replicas.admission(access)
                        if (latest is AppResult.Success && latest.value.denial != null)
                            AppResult.Failed(requireNotNull(latest.value.denial))
                        else
                            failed(
                                (latest as? AppResult.Failed)?.failure
                                    ?: collaborationListConflict().failure
                            )
                    }
            }
        }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)
}
