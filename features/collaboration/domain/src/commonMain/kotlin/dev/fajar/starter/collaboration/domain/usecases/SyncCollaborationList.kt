@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded remote acquisition. Interactive metadata writes do not wait for this network owner. */
class SyncCollaborationList(
    private val remote: CollaborationSummarySyncRepository,
    private val cache: CollaborationListSyncReplicaRepository,
    private val reads: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(workspaceId: String) =
        invoke(CollaborationOperationScope.Selected(workspaceId))

    suspend operator fun invoke(
        scope: CollaborationOperationScope
    ): AppResult<CollaborationListSyncOutcome> =
        withTimeoutOrNull(30_000) { execution.withLock { synchronize(scope) } }
            ?: AppResult.Success(CollaborationListSyncOutcome(false, 1))

    private suspend fun synchronize(
        scope: CollaborationOperationScope
    ): AppResult<CollaborationListSyncOutcome> {
        val owner =
            when (val captured = capture(scope)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val access = owner.localListAccess()
        val retainedAdmission = admission(scope, owner)
        val admission =
            when (retainedAdmission) {
                is AppResult.Failed -> return retainedAdmission
                is AppResult.Success -> retainedAdmission.value
            }
        var denial = admission.denial
        fun failed(failure: Failure): AppResult.Failed =
            AppResult.Failed(
                if (
                    denial?.let(::collaborationListResponseClosesRead) == true &&
                        !collaborationListResponseClosesRead(failure)
                )
                    requireNotNull(denial)
                else failure
            )
        suspend fun verify(received: AppResult<*>? = null): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            return requireCollaborationOwner(capture(scope), owner, received)
        }
        val initialOwner = verify()
        if (initialOwner is AppResult.Failed) return failed(initialOwner.failure)
        val stored = cache.syncWindow(access, emptyList())
        val storedOwner = verify(stored)
        if (storedOwner is AppResult.Failed) return failed(storedOwner.failure)
        if (stored is AppResult.Failed) return failed(stored.failure)
        var checkpoint = (stored as AppResult.Success).value.checkpoint
        suspend fun begin(): AppResult<Boolean> {
            val before = verify()
            if (before is AppResult.Failed) return before
            val fresh =
                CollaborationListSyncCheckpoint(
                    CollaborationListCheckpoint(Uuid.random().toString(), 0, 0, clock.now(), 2),
                    false,
                    CollaborationListSyncState(
                        owner.nativeSessionId,
                        CollaborationListSyncPhase.BOOTSTRAP,
                        null,
                    ),
                )
            val started = cache.beginSync(access, checkpoint, fresh)
            val after = verify(started)
            if (after is AppResult.Failed) return after
            if (started is AppResult.Success && started.value) checkpoint = fresh
            return started
        }
        if (
            checkpoint?.sync?.nativeSessionId != owner.nativeSessionId ||
                checkpoint?.local?.format != 2 ||
                (denial != null && checkpoint?.published == true)
        ) {
            when (val started = begin()) {
                is AppResult.Failed -> return failed(started.failure)
                is AppResult.Success ->
                    if (!started.value) return failed(collaborationListConflict().failure)
            }
        }
        var restarted = false
        repeat(10) {
            val before = verify()
            if (before is AppResult.Failed) return failed(before.failure)
            val current = requireNotNull(checkpoint)
            val state = requireNotNull(current.sync)
            val response = acquire(owner, state)
            // Preserve a confirmed denial even when cancellation arrives with the response.
            if (
                response is AppResult.Failed &&
                    collaborationListResponseClosesRead(response.failure)
            ) {
                denial = response.failure
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { reads.restrict(access, response.failure) }
                }
            }
            val acquiredOwner = verify(response)
            if (acquiredOwner is AppResult.Failed) return failed(acquiredOwner.failure)
            if (response is AppResult.Failed) {
                if (
                    !restarted &&
                        !collaborationListResponseClosesRead(response.failure) &&
                        collaborationSummaryCursorNeedsReset(response.failure)
                ) {
                    restarted = true
                    when (val started = begin()) {
                        is AppResult.Failed -> return failed(started.failure)
                        is AppResult.Success ->
                            if (!started.value) return failed(collaborationListConflict().failure)
                    }
                    return@repeat
                }
                return failed(response.failure)
            }
            val planned = plan(scope, owner, current, (response as AppResult.Success).value)
            if (planned is AppResult.Failed) return failed(planned.failure)
            val write = (planned as AppResult.Success).value
            val committed = cache.commitSync(access, current, write.checkpoint, write.facts)
            val committedOwner = verify(committed)
            if (committedOwner is AppResult.Failed) return failed(committedOwner.failure)
            when (committed) {
                is AppResult.Failed -> return failed(committed.failure)
                is AppResult.Success ->
                    if (!committed.value) return failed(collaborationListConflict().failure)
            }
            checkpoint = write.checkpoint
            if (write.caughtUp) {
                val reopened = admit(scope, owner, admission.revision)
                if (reopened is AppResult.Failed) return failed(reopened.failure)
                return AppResult.Success(CollaborationListSyncOutcome(true, 0))
            }
            if (write.retryAfterSeconds > 0)
                return AppResult.Success(
                    CollaborationListSyncOutcome(false, write.retryAfterSeconds)
                )
        }
        return AppResult.Success(CollaborationListSyncOutcome(false, 1))
    }

    private suspend fun plan(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        expected: CollaborationListSyncCheckpoint,
        page: SummaryResponse,
    ): AppResult<CollaborationListSyncWrite> {
        val identities =
            when (page) {
                is SummaryResponse.Bootstrap -> page.value.items.map { it.conversationId }
                is SummaryResponse.Changes -> page.value.items.map { it.fact.conversationId }
            }.distinct()
        val window = cache.syncWindow(owner.localListAccess(), identities)
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(scope), owner, window)
        if (current is AppResult.Failed) return current
        if (window is AppResult.Failed) return window
        val retained = (window as AppResult.Success).value
        if (retained.checkpoint != expected) return collaborationListConflict()
        return when (page) {
            is SummaryResponse.Bootstrap ->
                planCollaborationSummaryBootstrap(expected, page.value, retained, clock.now())
            is SummaryResponse.Changes ->
                planCollaborationSummaryChanges(expected, page.value, retained, clock.now())
        }
    }

    private suspend fun admission(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
    ): AppResult<CollaborationListAdmission> {
        val result = reads.admission(owner.localListAccess())
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(scope), owner, result)
        return if (current is AppResult.Failed) current else result
    }

    private suspend fun admit(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        revision: String?,
    ): AppResult<Unit> {
        val reopened = reads.admit(owner.localListAccess(), revision)
        currentCoroutineContext().ensureActive()
        val current = requireCollaborationOwner(capture(scope), owner, reopened)
        if (current is AppResult.Failed) return current
        if (reopened is AppResult.Failed) return reopened
        if ((reopened as AppResult.Success).value) return AppResult.Success(Unit)
        val latest = admission(scope, owner)
        if (latest is AppResult.Failed) return latest
        return AppResult.Failed(
            (latest as AppResult.Success).value.denial ?: collaborationListConflict().failure
        )
    }

    private suspend fun acquire(
        owner: CollaborationOwner,
        state: CollaborationListSyncState,
    ): AppResult<SummaryResponse> =
        if (state.phase == CollaborationListSyncPhase.BOOTSTRAP) {
            when (val result = remote.bootstrap(owner, state.cursor, 50)) {
                is AppResult.Failed -> result
                is AppResult.Success -> AppResult.Success(SummaryResponse.Bootstrap(result.value))
            }
        } else {
            when (val result = remote.changes(owner, requireNotNull(state.cursor), 50)) {
                is AppResult.Failed -> result
                is AppResult.Success -> AppResult.Success(SummaryResponse.Changes(result.value))
            }
        }

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)
}

private sealed interface SummaryResponse {
    data class Bootstrap(val value: CollaborationSummaryBootstrap) : SummaryResponse

    data class Changes(val value: CollaborationSummaryChanges) : SummaryResponse
}
