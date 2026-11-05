package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** One explicit native metadata read. Never enrolls, polls, clears admission or opens an SDK. */
class ReadCollaborationSpaceDeviceStatus(
    private val devices: CollaborationSpaceDeviceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner
    ): AppResult<CollaborationSpaceDeviceObservation> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationMessageId(owner.spaceId) || build.number !in 1..999_999_999L)
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val access = owner.localDirectoryAccess()
        var denial: Failure? = null
        fun failed(value: Failure) = AppResult.Failed(denial ?: value)
        return withTimeoutOrNull(15_000) {
            val initial =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (initial is AppResult.Failed) return@withTimeoutOrNull initial
            val deadline =
                Instant.fromEpochMilliseconds(
                    (initial as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            suspend fun current(): AppResult<Unit> {
                val captured =
                    sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
                if (captured is AppResult.Failed) return captured
                val now = clock.now()
                if (
                    now < requestedAt ||
                        now >= deadline ||
                        now - requestedAt >= 15.seconds ||
                        started.elapsedNow() >= minOf(15.seconds, deadline - requestedAt)
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_revalidation_required",
                        FailureKind.Unavailable,
                    )
                return AppResult.Success(Unit)
            }
            val admission = directory.admission(access)
            if (admission is AppResult.Failed) return@withTimeoutOrNull admission
            val previous = (admission as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(previous))
                return@withTimeoutOrNull invalid()
            denial = previous.denial
            val before = current()
            if (before is AppResult.Failed) return@withTimeoutOrNull failed(before.failure)
            val received = devices.status(owner)
            if (
                received is AppResult.Failed &&
                    collaborationSpaceDirectoryResponseClosesRead(received.failure)
            ) {
                denial = received.failure
                // Keep a confirmed denial even if cancellation arrived with the response.
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { directory.restrict(access, received.failure) }
                }
            }
            currentCoroutineContext().ensureActive()
            val after = current()
            if (after is AppResult.Failed) return@withTimeoutOrNull failed(after.failure)
            if (received is AppResult.Failed) return@withTimeoutOrNull failed(received.failure)
            val value = (received as AppResult.Success).value
            val expectedPlatform =
                if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
                else CollaborationNativePlatform.IOS
            if (
                value.owner != owner ||
                    value.device?.let {
                        !validCollaborationSpaceDeviceStatus(it) || it.platform != expectedPlatform
                    } == true
            )
                return@withTimeoutOrNull failed(invalid().failure)
            val latest = directory.admission(access)
            val afterAdmission = current()
            if (afterAdmission is AppResult.Failed)
                return@withTimeoutOrNull failed(afterAdmission.failure)
            if (latest is AppResult.Failed) return@withTimeoutOrNull failed(latest.failure)
            val gate = (latest as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate))
                return@withTimeoutOrNull failed(invalid().failure)
            if (gate.denial != null && gate.revision != previous.revision)
                return@withTimeoutOrNull AppResult.Failed(gate.denial)
            // Successful metadata may help recovery, but cannot reopen a denied directory.
            AppResult.Success(value)
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Device status timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    private fun invalid() =
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
