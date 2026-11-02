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

/** Explicit summary acquisition. Pages and individual conversations share one publication mutex. */
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

    /** Resolve an opaque room hint under the same capture, publication and denial gate as reads. */
    suspend fun byRoom(
        scope: CollaborationOperationScope,
        roomId: String,
    ): AppResult<CollaborationRead<CollaborationDirectSummary>> {
        currentCoroutineContext().ensureActive()
        if (
            roomId.length !in 2..255 ||
                !roomId.startsWith('!') ||
                roomId.any { it.code !in 33..126 }
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return acquire(
            scope,
            { conversations.summaryByRoom(it, roomId) },
            { listOf(it) },
            ::collaborationContentResponseClosesRead,
        )
    }

    suspend operator fun invoke(workspaceId: String, after: String? = null, limit: Int = 30) =
        invoke(CollaborationOperationScope.Selected(workspaceId), after, limit)

    suspend fun conversation(workspaceId: String, conversationId: String) =
        conversation(CollaborationOperationScope.Selected(workspaceId), conversationId)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        after: String? = null,
        limit: Int = 30,
    ): AppResult<CollaborationRead<CollaborationPage<CollaborationDirectSummary>>> =
        acquire(
            scope,
            { conversations.summaries(it, after, limit) },
            { it.items },
            ::collaborationListResponseClosesRead,
        )

    /** Acquires a new/opened DM directly, including one absent from the first directory page. */
    suspend fun conversation(
        scope: CollaborationOperationScope,
        conversationId: String,
    ): AppResult<CollaborationRead<CollaborationDirectSummary>> =
        acquire(
            scope,
            { owner ->
                when (val received = conversations.summary(owner, conversationId)) {
                    is AppResult.Failed -> received
                    is AppResult.Success ->
                        if (received.value.conversation.id == conversationId) received
                        else
                            collaborationSetupFailure(
                                "client.collaboration_conversation_mismatch",
                                FailureKind.Unexpected,
                            )
                }
            },
            { listOf(it) },
            ::collaborationContentResponseClosesRead,
        )

    private suspend fun <T> acquire(
        scope: CollaborationOperationScope,
        read: suspend (CollaborationOwner) -> AppResult<T>,
        items: (T) -> List<CollaborationDirectSummary>,
        closesRead: (Failure) -> Boolean,
    ): AppResult<CollaborationRead<T>> =
        acquisition.withLock {
            val owner =
                when (val captured = capture(scope)) {
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
                            !closesRead(failure)
                    )
                        requireNotNull(retainedDenial)
                    else failure
                )
            val checkpoint =
                when (val current = replicas.checkpoint(access)) {
                    is AppResult.Failed -> return@withLock failed(current.failure)
                    is AppResult.Success -> current.value
                }
            val before = requireCollaborationOwner(capture(scope), owner, null)
            if (before is AppResult.Failed) return@withLock failed(before.failure)
            val received = read(owner)
            // A received denial must reach the process gate even if the route is cancelled now.
            if (received is AppResult.Failed && closesRead(received.failure)) {
                retainedDenial = received.failure
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { replicas.restrict(access, received.failure) }
                }
            }
            currentCoroutineContext().ensureActive()
            val admitted = requireCollaborationOwner(capture(scope), owner, received)
            if (admitted is AppResult.Failed) return@withLock failed(admitted.failure)
            if (received is AppResult.Failed) return@withLock failed(received.failure)
            val page = (received as AppResult.Success).value
            val merged = replicas.merge(access, checkpoint, items(page), clock.now())
            currentCoroutineContext().ensureActive()
            val afterWrite = requireCollaborationOwner(capture(scope), owner, merged)
            if (afterWrite is AppResult.Failed) return@withLock failed(afterWrite.failure)
            when (merged) {
                is AppResult.Failed -> return@withLock failed(merged.failure)
                is AppResult.Success ->
                    if (!merged.value) return@withLock failed(collaborationListConflict().failure)
            }
            val reopened = replicas.admit(access, admission.revision)
            currentCoroutineContext().ensureActive()
            val finalOwner = requireCollaborationOwner(capture(scope), owner, reopened)
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

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)
}
