package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRootRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*

/**
 * Persists an already acquired discovery, without reopening reads or creating provider identity.
 */
class PublishKnownCollaborationSpace(
    private val roots: CollaborationSpaceRootRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        discovery: CollaborationSpaceDiscovery
    ): AppResult<CollaborationSpaceRoot> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(2_000) {
            val before = current(discovery)
            if (before is AppResult.Failed) return@withTimeoutOrNull before
            val root = CollaborationSpaceRoot(discovery.owner.spaceId)
            val saved = roots.retain(discovery.owner.parent.localSpaceAccountAccess(), root)
            currentCoroutineContext().ensureActive()
            val after = current(discovery)
            if (after is AppResult.Failed) return@withTimeoutOrNull after
            when (saved) {
                is AppResult.Failed -> saved
                is AppResult.Success ->
                    if (saved.value) AppResult.Success(root) else afterRefusal(discovery, root)
            }
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_recovery_timeout",
                FailureKind.Timeout,
            )
    }

    private suspend fun afterRefusal(
        discovery: CollaborationSpaceDiscovery,
        root: CollaborationSpaceRoot,
    ): AppResult<CollaborationSpaceRoot> {
        val known = roots.current(discovery.owner.parent.localSpaceAccountAccess())
        currentCoroutineContext().ensureActive()
        val after = current(discovery)
        if (after is AppResult.Failed) return after
        return when (known) {
            is AppResult.Failed -> known
            is AppResult.Success ->
                when (known.value) {
                    root -> AppResult.Success(root)
                    null ->
                        collaborationRecoveryFailure(
                            "client.collaboration_root_changed",
                            FailureKind.Conflict,
                        )
                    else ->
                        collaborationRecoveryFailure(
                            "client.collaboration_space_migration_required",
                            FailureKind.Conflict,
                        )
                }
        }
    }

    private suspend fun current(discovery: CollaborationSpaceDiscovery): AppResult<Unit> {
        val parent = discovery.owner.parent
        val captured = sessions.captureCollaborationSpaceParent(parent, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val now = clock.now()
        val lifetime = discovery.observation.expiresAt - discovery.observation.admittedAt
        val nativeDeadline =
            Instant.fromEpochMilliseconds(
                (captured as AppResult.Success).value.binding.expiresAtEpochMillis
            )
        if (
            !validCollaborationSpaceObservation(discovery.observation, parent) ||
                discovery.requestedAt.toEpochMilliseconds() < 0 ||
                discovery.expiresAt <= discovery.requestedAt ||
                discovery.expiresAt > discovery.requestedAt + lifetime ||
                discovery.expiresAt > nativeDeadline
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_admission",
                FailureKind.Unexpected,
            )
        if (
            now < discovery.requestedAt ||
                now >= discovery.expiresAt ||
                discovery.monotonicDeadline.hasPassedNow()
        )
            return collaborationRecoveryFailure(
                "client.collaboration_revalidation_required",
                FailureKind.Unavailable,
            )
        return AppResult.Success(Unit)
    }
}
