package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/**
 * One current native discovery. Never selects a workspace, changes cached reads or opens SDK I/O.
 */
class DiscoverCollaborationSpace(
    private val spaces: CollaborationSpaceRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        parent: CollaborationNativeParent
    ): AppResult<CollaborationSpaceDiscovery> =
        withTimeoutOrNull(15_000) { discover(parent) }
            ?: collaborationRecoveryFailure(
                "client.collaboration_recovery_timeout",
                FailureKind.Timeout,
            )

    private suspend fun discover(
        parent: CollaborationNativeParent
    ): AppResult<CollaborationSpaceDiscovery> {
        val initial = sessions.captureCollaborationSpaceParent(parent, server, build, clock)
        if (initial is AppResult.Failed) return initial
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val result = spaces.current(parent)
        currentCoroutineContext().ensureActive()
        val current = sessions.captureCollaborationSpaceParent(parent, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed) return result
        if (current is AppResult.Failed) return current
        val value = (result as AppResult.Success).value
        if (!validCollaborationSpaceObservation(value, parent))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_admission",
                FailureKind.Unexpected,
            )
        val before = (initial as AppResult.Success).value
        val after = (current as AppResult.Success).value
        // Translate the remote duration from dispatch, including transport time and both parents.
        val deadline =
            minOf(
                requestedAt + (value.expiresAt - value.admittedAt),
                Instant.fromEpochMilliseconds(before.binding.expiresAtEpochMillis),
                Instant.fromEpochMilliseconds(after.binding.expiresAtEpochMillis),
            )
        val monotonic = started + (deadline - requestedAt)
        val now = clock.now()
        if (
            now < requestedAt ||
                now >= deadline ||
                now - requestedAt >= 15.seconds ||
                started.elapsedNow() >= 15.seconds ||
                monotonic.hasPassedNow()
        )
            return collaborationRecoveryFailure(
                "client.collaboration_revalidation_required",
                FailureKind.Unavailable,
            )
        return AppResult.Success(
            CollaborationSpaceDiscovery(value, requestedAt, deadline, monotonic)
        )
    }
}
