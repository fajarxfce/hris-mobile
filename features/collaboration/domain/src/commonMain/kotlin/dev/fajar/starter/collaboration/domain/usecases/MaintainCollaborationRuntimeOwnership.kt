package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.companyPolicyDeadline
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** One application collector closes obsolete SDK owners. It never opens or retries a session. */
class MaintainCollaborationRuntimeOwnership(
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
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
                    val owner = (candidate as? AppResult.Success)?.value
                    val closed = runtimes.closeOtherOwners(owner)
                    currentCoroutineContext().ensureActive()
                    // A confirmed denial stays authoritative even if native cleanup failed.
                    emit(
                        if (candidate is AppResult.Failed) candidate
                        else if (closed is AppResult.Failed) closed else candidate
                    )
                }
            )
        } finally {
            try {
                withContext(NonCancellable) { runtimes.closeOtherOwners(null) }
            } finally {
                observation.unlock()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun owners(): Flow<AppResult<CollaborationOwner?>> =
        sessions.observe().flatMapLatest { identity ->
            when (identity) {
                is AppResult.Failed -> flowOf(identity)
                is AppResult.Success -> {
                    val session = identity.value
                    if (session == null) flowOf(AppResult.Success(null))
                    else
                        workspaces.observe(session.id).flatMapLatest { selected ->
                            when (selected) {
                                is AppResult.Failed -> flowOf(selected)
                                is AppResult.Success -> admitted(session, selected.value)
                            }
                        }
                }
            }
        }

    private fun admitted(
        session: Session,
        workspace: Workspace?,
    ): Flow<AppResult<CollaborationOwner?>> = flow {
        if (workspace == null) {
            emit(AppResult.Success(null))
            return@flow
        }
        val now = clock.now()
        val owner = collaborationRequestOwner(workspace.id, session, workspace, server, build, now)
        emit(owner)
        if (owner is AppResult.Failed) return@flow
        val policy = checkNotNull(workspace.policy)
        val maintenance =
            policy.policy.maintenance
                ?.let { policy.requestedAt + (it.startsAt - policy.policy.serverTime) }
                ?.takeIf { it > now }
        val deadline =
            listOfNotNull(
                    Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
                    workspace.admission.expiresAt,
                    companyPolicyDeadline(policy),
                    maintenance,
                )
                .min()
        delay((deadline - now).inWholeMilliseconds.coerceAtLeast(0))
        // A clock rollback cannot renew captured authority. A fresh repository
        // emission can be admitted again; no timer polling or silent reopening.
        emit(
            collaborationSetupFailure(
                "client.collaboration_revalidation_required",
                FailureKind.Unavailable,
            )
        )
    }
}
