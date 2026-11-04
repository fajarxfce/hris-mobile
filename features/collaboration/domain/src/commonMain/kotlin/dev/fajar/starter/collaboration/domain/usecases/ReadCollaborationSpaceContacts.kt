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

/** One explicit page acquisition. A terminal cursor schedules no subsequent request. */
class ReadCollaborationSpaceContacts(
    private val spaces: CollaborationSpaceRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        query: String = "",
        after: String? = null,
        limit: Int = 30,
    ): AppResult<CollaborationSpaceContactPage> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceContactRequest(owner, query, after, limit))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        return withTimeoutOrNull(15_000) { read(owner, query.trim(), after, limit) }
            ?: collaborationRecoveryFailure(
                "client.collaboration_recovery_timeout",
                FailureKind.Timeout,
            )
    }

    private suspend fun read(
        owner: CollaborationSpaceOwner,
        query: String,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceContactPage> {
        val initial = sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
        if (initial is AppResult.Failed) return initial
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val parentDeadline =
            Instant.fromEpochMilliseconds(
                (initial as AppResult.Success).value.binding.expiresAtEpochMillis
            )
        val result = spaces.contacts(owner, query, after, limit)
        currentCoroutineContext().ensureActive()
        val current = sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed) return result
        if (current is AppResult.Failed) return current
        val now = clock.now()
        if (
            now < requestedAt ||
                now >= parentDeadline ||
                now - requestedAt >= 15.seconds ||
                started.elapsedNow() >= minOf(15.seconds, parentDeadline - requestedAt)
        )
            return collaborationRecoveryFailure(
                "client.collaboration_revalidation_required",
                FailureKind.Unavailable,
            )
        val page = (result as AppResult.Success).value
        if (!validCollaborationSpaceContactPage(page, owner, after, limit))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        return AppResult.Success(page)
    }
}
