package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Invocation-owned checks shared by native setup operations, never a nested use case or job. */
internal class CollaborationSpaceSetupAttempt(
    val owner: CollaborationSpaceOwner,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val requestedAt: Instant,
    private val started: TimeMark,
    val hardDeadline: Instant,
) {
    val access = owner.localDirectoryAccess()
    private var denial: Failure? = null

    val restriction: Failure?
        get() = denial

    fun remember(value: Failure?) {
        if (value != null && collaborationSpaceDirectoryResponseClosesRead(value)) denial = value
    }

    fun failed(value: Failure) = AppResult.Failed(denial ?: value)

    suspend fun current(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val acquired = sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
        if (acquired is AppResult.Failed) return failed(acquired.failure)
        val now = clock.now()
        if (
            now < requestedAt ||
                now >= hardDeadline ||
                now - requestedAt >= 15.seconds ||
                started.elapsedNow() >= minOf(15.seconds, hardDeadline - requestedAt)
        )
            return failed(
                collaborationRecoveryFailure(
                        "client.collaboration_revalidation_required",
                        FailureKind.Unavailable,
                    )
                    .failure
            )
        return AppResult.Success(Unit)
    }

    suspend fun admission(): AppResult<CollaborationSpaceDirectoryAdmission> {
        val before = current()
        if (before is AppResult.Failed) return before
        val received = directory.admission(access)
        if (
            received is AppResult.Success &&
                validCollaborationSpaceDirectoryAdmission(received.value)
        )
            remember(received.value.denial)
        val after = current()
        if (after is AppResult.Failed) return after
        if (received is AppResult.Failed) return failed(received.failure)
        val value = (received as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(value))
            return failed(invalidSpaceSetup().failure)
        remember(value.denial)
        return received
    }

    suspend fun unchangedAdmission(
        previous: CollaborationSpaceDirectoryAdmission
    ): AppResult<Unit> {
        val latest = admission()
        if (latest is AppResult.Failed) return latest
        val value = (latest as AppResult.Success).value
        return if (value.denial != null && value.revision != previous.revision) failed(value.denial)
        else AppResult.Success(Unit)
    }

    suspend fun restrict(received: AppResult<*>) {
        if (
            received is AppResult.Failed &&
                collaborationSpaceDirectoryResponseClosesRead(received.failure)
        ) {
            remember(received.failure)
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { directory.restrict(access, received.failure) }
            }
        }
    }
}

internal fun invalidSpaceSetup() =
    collaborationRecoveryFailure("client.collaboration_invalid_response", FailureKind.Unexpected)

internal fun changedSpaceSetup() =
    collaborationRecoveryFailure("client.collaboration_command_changed", FailureKind.Conflict)
