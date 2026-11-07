package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlinx.coroutines.*

/** Owns current admission and final publication, independently of the selected HR company. */
class AdoptCollaborationSpaceRuntime(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val originals: CollaborationRuntimeRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        registrationId: String,
        operationId: String,
    ): AppResult<CollaborationSpaceRuntimeReady> {
        currentCoroutineContext().ensureActive()
        if (
            listOf(owner.spaceId, registrationId, operationId).any {
                !validCollaborationMessageId(it)
            }
        )
            return changedSpaceSetup()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var attempt: CollaborationSpaceSetupAttempt? = null
        var rejected: Failure? = null
        try {
            val result =
                withTimeoutOrNull(15_000) {
                    val parent =
                        sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
                    if (parent is AppResult.Failed) return@withTimeoutOrNull parent
                    val current =
                        CollaborationSpaceSetupAttempt(
                            owner,
                            directory,
                            sessions,
                            server,
                            build,
                            clock,
                            requestedAt,
                            started,
                            Instant.fromEpochMilliseconds(
                                (parent as AppResult.Success).value.binding.expiresAtEpochMillis
                            ),
                        )
                    attempt = current
                    adopt(current, registrationId, operationId)
                } ?: (attempt?.failed(timeout().failure) ?: timeout())
            if (
                result is AppResult.Failed &&
                    collaborationSpaceDirectoryResponseClosesRead(result.failure)
            )
                rejected = result.failure
            return result
        } finally {
            if (rejected != null || attempt?.restriction != null) {
                // A cleanup failure cannot replace a confirmed denial or resurrect the runtime.
                withContext(NonCancellable) { runCatching { runtimes.closeSpace(owner) } }
            }
        }
    }

    private suspend fun adopt(
        attempt: CollaborationSpaceSetupAttempt,
        registrationId: String,
        operationId: String,
    ): AppResult<CollaborationSpaceRuntimeReady> {
        val admitted = attempt.admission()
        if (admitted is AppResult.Failed) return admitted
        val gate = (admitted as AppResult.Success).value
        gate.denial?.let {
            return attempt.failed(it)
        }
        val selected = select(attempt, registrationId, operationId)
        if (selected is AppResult.Failed) return selected
        val source = (selected as AppResult.Success).value
        val before = revalidate(attempt, gate, source)
        if (before is AppResult.Failed) return before
        val open = runtimes.isSpaceOpen(attempt.owner, source.original.intent.deviceId, operationId)
        val after = attempt.unchangedAdmission(gate)
        if (after is AppResult.Failed) return after
        if (open is AppResult.Failed) return attempt.failed(open.failure)
        if ((open as AppResult.Success).value) {
            val final = revalidate(attempt, gate, source)
            if (final is AppResult.Failed) return final
            val stillOpen =
                runtimes.isSpaceOpen(attempt.owner, source.original.intent.deviceId, operationId)
            val current = attempt.unchangedAdmission(gate)
            if (current is AppResult.Failed) return current
            if (stillOpen is AppResult.Failed) return attempt.failed(stillOpen.failure)
            return if ((stillOpen as AppResult.Success).value) ready(attempt.owner, source)
            else changedSpaceSetup()
        }
        return install(attempt, gate, source)
    }

    private suspend fun select(
        attempt: CollaborationSpaceSetupAttempt,
        registrationId: String,
        operationId: String,
    ): AppResult<CollaborationSpaceRuntimeAdoption> {
        val readRegistration =
            registrations.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterRegistration = attempt.current()
        if (afterRegistration is AppResult.Failed) return afterRegistration
        if (readRegistration is AppResult.Failed) return attempt.failed(readRegistration.failure)
        val registration =
            (readRegistration as AppResult.Success).value ?: return changedSpaceSetup()
        val checked = spaceSetupOriginal(attempt.owner, registrationId, registration)
        if (checked is AppResult.Failed) return checked
        val legacy = requireNotNull(registration.command.legacy)
        if (
            legacy.binding.nativeSessionId != attempt.owner.parent.nativeSessionId ||
                legacy.binding.audience != attempt.owner.parent.audience ||
                legacy.binding.accountId != attempt.owner.parent.accountId
        )
            return changedSpaceSetup()
        val readSetup = commands.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterSetup = attempt.current()
        if (afterSetup is AppResult.Failed) return afterSetup
        if (readSetup is AppResult.Failed) return attempt.failed(readSetup.failure)
        val setup = (readSetup as AppResult.Success).value ?: return changedSpaceSetup()
        if (
            setup.command.id != operationId ||
                setup.command.platform.name != build.platform.name.uppercase()
        )
            return changedSpaceSetup()
        val physical =
            CollaborationOwner(
                attempt.owner.parent.sessionId,
                legacy.binding.audience,
                legacy.binding.accountId,
                legacy.binding.companyId,
                legacy.binding.nativeSessionId,
            )
        val readIntent = originals.handoverIntent(physical, legacy.deviceId)
        val afterIntent = attempt.current()
        if (afterIntent is AppResult.Failed) return afterIntent
        if (readIntent is AppResult.Failed) return attempt.failed(readIntent.failure)
        val intent = (readIntent as AppResult.Success).value ?: return changedSpaceSetup()
        val selected =
            CollaborationSpaceRuntimeAdoption(
                registration,
                setup,
                CollaborationExistingRuntimeHandover(legacy, intent),
            )
        return if (validCollaborationSpaceRuntimeAdoption(attempt.owner, selected))
            AppResult.Success(selected)
        else changedSpaceSetup()
    }

    private suspend fun revalidate(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        source: CollaborationSpaceRuntimeAdoption,
    ): AppResult<Unit> {
        val before = attempt.current()
        if (before is AppResult.Failed) return before
        val started = timeSource.markNow()
        val status = devices.status(attempt.owner)
        attempt.restrict(status)
        val admitted = attempt.unchangedAdmission(gate)
        if (admitted is AppResult.Failed) return admitted
        if (status is AppResult.Failed) return attempt.failed(status.failure)
        val observed = (status as AppResult.Success).value
        val device = observed.device
        if (
            observed.owner == attempt.owner &&
                device != null &&
                validCollaborationSpaceDeviceStatus(device) &&
                device.id == source.original.intent.deviceId &&
                (device.state == CollaborationDeviceState.REVOKED ||
                    device.gateway?.let {
                        it.id == source.gateway?.sessionId && it.revokedAt != null
                    } == true)
        ) {
            val denied =
                collaborationRecoveryFailure(
                    "collaboration_access_denied",
                    FailureKind.AccessDenied,
                )
            attempt.restrict(denied)
            return denied
        }
        val registration =
            registrations.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterRegistration = attempt.unchangedAdmission(gate)
        if (afterRegistration is AppResult.Failed) return afterRegistration
        if (registration is AppResult.Failed) return attempt.failed(registration.failure)
        val setup = commands.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterSetup = attempt.unchangedAdmission(gate)
        if (afterSetup is AppResult.Failed) return afterSetup
        if (setup is AppResult.Failed) return attempt.failed(setup.failure)
        if (
            (registration as AppResult.Success).value != source.registration ||
                (setup as AppResult.Success).value != source.setup
        )
            return changedSpaceSetup()
        return if (
            freshCollaborationSpaceRuntimeDevice(
                attempt.owner,
                source,
                observed,
                started.elapsedNow(),
            )
        )
            AppResult.Success(Unit)
        else changedSpaceSetup()
    }

    private suspend fun install(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        source: CollaborationSpaceRuntimeAdoption,
    ): AppResult<CollaborationSpaceRuntimeReady> {
        var lease: CollaborationSpaceRuntimeLease? = null
        var published = false
        try {
            val received = runtimes.installOriginal(attempt.owner, source.original, source.setup)
            // Capture non-cooperative late success before checking cancellation or admission.
            if (received is AppResult.Success) lease = received.value
            val current = attempt.unchangedAdmission(gate)
            if (current is AppResult.Failed) return current
            if (received is AppResult.Failed) return attempt.failed(received.failure)
            val checked = revalidate(attempt, gate, source)
            if (checked is AppResult.Failed) return checked
            val resource = requireNotNull(lease)
            if (
                resource.owner != attempt.owner ||
                    resource.deviceId != source.original.intent.deviceId ||
                    resource.installedOperationId != source.setup.command.id
            )
                return changedSpaceSetup()
            resource.credentialFailures.value?.let {
                return attempt.failed(it)
            }
            currentCoroutineContext().ensureActive()
            val accepted = resource.publish()
            if (accepted is AppResult.Failed) return attempt.failed(accepted.failure)
            published = true
            return ready(attempt.owner, source)
        } finally {
            if (!published) withContext(NonCancellable) { runCatching { lease?.close() } }
        }
    }

    private fun ready(owner: CollaborationSpaceOwner, source: CollaborationSpaceRuntimeAdoption) =
        AppResult.Success(
            CollaborationSpaceRuntimeReady(
                owner,
                source.original.intent.deviceId,
                source.setup.command.id,
            )
        )

    private fun timeout() =
        collaborationRecoveryFailure("client.collaboration_recovery_timeout", FailureKind.Timeout)
}
