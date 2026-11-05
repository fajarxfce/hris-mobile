package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.time.Instant

internal suspend fun SessionRepository.currentCollaborationSpaceDiscovery(
    discovery: CollaborationSpaceDiscovery,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Unit> {
    val parent = discovery.owner.parent
    val captured = captureCollaborationSpaceParent(parent, server, build, clock)
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
