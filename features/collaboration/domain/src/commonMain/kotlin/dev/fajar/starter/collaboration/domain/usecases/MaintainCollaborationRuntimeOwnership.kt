package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.companyPolicyDeadline
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** One application collector closes obsolete SDK owners. It never opens or retries a session. */
class MaintainCollaborationRuntimeOwnership(
    private val runtimes: CollaborationRuntimeRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val observation = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<CollaborationOwner?>> = flow {
        if (!observation.tryLock()) {
            emit(collaborationSetupFailure("client.collaboration_owner_busy"))
            return@flow
        }
        try {
            emitAll(
                owners().distinctUntilChanged().transformLatest { candidate ->
                    candidate.restriction?.let { (access, failure) ->
                        withContext(NonCancellable) {
                            withTimeoutOrNull(2_000) { lists.restrict(access, failure) }
                        }
                    }
                    val closed = runtimes.updateRetention(candidate.retention)
                    currentCoroutineContext().ensureActive()
                    // Keep a confirmed denial even when native cleanup cannot finish yet.
                    emit(
                        if (candidate.result is AppResult.Failed) candidate.result
                        else if (closed is AppResult.Failed) closed else candidate.result
                    )
                }
            )
        } finally {
            try {
                withContext(NonCancellable) {
                    runtimes.updateRetention(CollaborationRuntimeRetention(null, null))
                }
            } finally {
                observation.unlock()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun owners(): Flow<RuntimeOwnershipDecision> =
        sessions.observe().flatMapLatest { identity ->
            when (identity) {
                is AppResult.Failed -> flowOf(decision(null, identity))
                is AppResult.Success -> {
                    val session = identity.value
                    if (session == null) flowOf(decision(null, AppResult.Success(null)))
                    else workspaces.observe(session.id).flatMapLatest { admitted(session, it) }
                }
            }
        }

    private fun admitted(
        session: Session,
        selected: AppResult<Workspace?>,
    ): Flow<RuntimeOwnershipDecision> = flow {
        val elapsed = timeSource.markNow()
        val now = clock.now()
        val captured = collaborationNativeParent(session, server, build, now)
        if (captured is AppResult.Failed) {
            emit(decision(null, captured))
            return@flow
        }
        val parent = (captured as AppResult.Success).value
        val workspace = (selected as? AppResult.Success)?.value
        val candidate =
            when {
                selected is AppResult.Failed -> selected
                workspace == null -> AppResult.Success(null)
                else ->
                    collaborationRequestOwner(workspace.id, session, workspace, server, build, now)
            }
        emit(decision(parent, candidate, workspace))
        val parentDeadline = Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis)
        val parentWait = (parentDeadline - now).inWholeMilliseconds.coerceAtLeast(0)
        val foregroundDeadline =
            if (candidate is AppResult.Success && candidate.value != null && workspace != null)
                foregroundDeadline(workspace, parentDeadline, now)
            else parentDeadline
        val firstWait = (foregroundDeadline - now).inWholeMilliseconds.coerceIn(0, parentWait)
        delay((firstWait - elapsed.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
        val expired =
            collaborationSetupFailure(
                "client.collaboration_revalidation_required",
                FailureKind.Unavailable,
            )
        if (firstWait < parentWait) {
            emit(decision(parent, expired))
            // Keep the original relative deadline even if the wall clock moves backward.
            delay((parentWait - elapsed.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
        }
        emit(decision(null, expired))
    }

    private fun foregroundDeadline(
        workspace: Workspace,
        parentDeadline: Instant,
        now: Instant,
    ): Instant {
        val policy = checkNotNull(workspace.policy)
        val maintenance =
            policy.policy.maintenance
                ?.let { policy.requestedAt + (it.startsAt - policy.policy.serverTime) }
                ?.takeIf { it > now }
        return listOfNotNull(
                parentDeadline,
                workspace.admission.expiresAt,
                companyPolicyDeadline(policy),
                maintenance,
            )
            .min()
    }

    private fun decision(
        parent: CollaborationNativeParent?,
        result: AppResult<CollaborationOwner?>,
        workspace: Workspace? = null,
    ): RuntimeOwnershipDecision {
        val owner = (result as? AppResult.Success)?.value
        val denied =
            (result as? AppResult.Failed)?.failure?.takeIf(::collaborationListResponseClosesRead)
        val deniedOwner =
            if (
                parent != null &&
                    workspace != null &&
                    denied != null &&
                    workspace.sessionId == parent.sessionId &&
                    workspace.audience == parent.audience &&
                    workspace.admission.accountId == parent.accountId &&
                    workspace.admission.familyId == parent.nativeSessionId
            )
                parent.owner(workspace.company.id)
            else null
        return RuntimeOwnershipDecision(
            CollaborationRuntimeRetention(parent, owner, deniedOwner?.companyId),
            result,
            if (deniedOwner != null && denied != null) deniedOwner.localListAccess() to denied
            else null,
        )
    }
}

private data class RuntimeOwnershipDecision(
    val retention: CollaborationRuntimeRetention,
    val result: AppResult<CollaborationOwner?>,
    val restriction: Pair<CollaborationLocalAccess, Failure>?,
)
