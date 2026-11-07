@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** One explicit original-store transfer step. No enrollment, SDK adoption or polling loop. */
class AdvanceCollaborationSpaceRuntimeTransfer(
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val transfers: CollaborationSpaceRuntimeTransferRepository,
    private val runtime: CollaborationRuntimeRepository,
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
    ): AppResult<CollaborationSpaceRuntimeTransferProgress> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationMessageId(owner.spaceId) ||
                !validCollaborationMessageId(registrationId)
        )
            return changedSpaceSetup()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var attempt: CollaborationSpaceSetupAttempt? = null
        return withTimeoutOrNull(15_000) {
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val deadline =
                Instant.fromEpochMilliseconds(
                    (captured as AppResult.Success).value.binding.expiresAtEpochMillis
                )
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
                    deadline,
                )
            attempt = current
            advance(current, registrationId)
        } ?: (attempt?.failed(timeout().failure) ?: timeout())
    }

    private suspend fun advance(
        attempt: CollaborationSpaceSetupAttempt,
        registrationId: String,
    ): AppResult<CollaborationSpaceRuntimeTransferProgress> {
        val admission = attempt.admission()
        if (admission is AppResult.Failed) return admission
        val gate = (admission as AppResult.Success).value
        val loaded = registrations.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterRead = attempt.current()
        if (afterRead is AppResult.Failed) return afterRead
        if (loaded is AppResult.Failed) return attempt.failed(loaded.failure)
        val original =
            spaceSetupOriginal(attempt.owner, registrationId, (loaded as AppResult.Success).value)
        if (original is AppResult.Failed) return attempt.failed(original.failure)
        val registered = (original as AppResult.Success).value
        val legacy = requireNotNull(registered.command.legacy)
        if (legacy.binding.nativeSessionId != attempt.owner.parent.nativeSessionId)
            return attempt.failed(changedSpaceSetup().failure)
        val physical =
            CollaborationOwner(
                attempt.owner.parent.sessionId,
                legacy.binding.audience,
                legacy.binding.accountId,
                legacy.binding.companyId,
                legacy.binding.nativeSessionId,
            )
        val retained = runtime.handoverIntent(physical, legacy.deviceId)
        val afterIntent = attempt.current()
        if (afterIntent is AppResult.Failed) return afterIntent
        if (retained is AppResult.Failed) return attempt.failed(retained.failure)
        val status = devices.status(attempt.owner)
        attempt.restrict(status)
        val afterStatus = attempt.unchangedAdmission(gate)
        if (afterStatus is AppResult.Failed) return afterStatus
        if (status is AppResult.Failed) return attempt.failed(status.failure)
        val checked =
            spaceSetupStatus(attempt.owner, registered, (status as AppResult.Success).value)
        if (checked is AppResult.Failed) return attempt.failed(checked.failure)
        val device = (checked as AppResult.Success).value
        val saved = (retained as AppResult.Success).value
        if (device.state == CollaborationDeviceState.ACTIVE && saved == null)
            return attempt.failed(changedSpaceSetup().failure)
        val transfer =
            CollaborationSpaceRuntimeTransfer(
                registered.command.binding,
                legacy,
                saved
                    ?: CollaborationRuntimeHandoverIntent(
                        Uuid.random().toString(),
                        attempt.owner.spaceId,
                        device.id,
                        device.version,
                        legacy.identity.fingerprint,
                    ),
            )
        if (!validCollaborationSpaceRuntimeTransferTarget(transfer, device))
            return attempt.failed(changedSpaceSetup().failure)
        if (device.state == CollaborationDeviceState.ACTIVE) {
            return AppResult.Success(
                CollaborationSpaceRuntimeTransferProgress.Provisioned(transfer, device.version)
            )
        }
        return verifyAndStage(attempt, gate, transfer)
    }

    private suspend fun verifyAndStage(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        transfer: CollaborationSpaceRuntimeTransfer,
    ): AppResult<CollaborationSpaceRuntimeTransferProgress> {
        val requested = timeSource.markNow()
        val received = transfers.publication(attempt.owner, transfer)
        attempt.restrict(received)
        val afterPublication = attempt.unchangedAdmission(gate)
        if (afterPublication is AppResult.Failed) return afterPublication
        if (received is AppResult.Failed) return attempt.failed(received.failure)
        val publication = (received as AppResult.Success).value
        if (
            !freshCollaborationSpaceLegacyPublication(transfer, publication, requested.elapsedNow())
        )
            return attempt.failed(revalidationRequired().failure)
        // The repository drains this exact SDK and proves private keys under the original lease.
        // A separate reader here would contend with the still-open original SDK.
        val staged =
            runtime.stageExistingHandover(
                attempt.owner.parent,
                CollaborationExistingRuntimeHandover(transfer.legacy, transfer.intent),
            )
        val afterStage = attempt.unchangedAdmission(gate)
        if (afterStage is AppResult.Failed) return afterStage
        if (staged is AppResult.Failed) return attempt.failed(staged.failure)
        if ((staged as AppResult.Success).value != transfer.intent)
            return attempt.failed(changedSpaceSetup().failure)
        if (
            !freshCollaborationSpaceLegacyPublication(transfer, publication, requested.elapsedNow())
        )
            return attempt.failed(revalidationRequired().failure)
        return freezeAndObserve(attempt, gate, transfer)
    }

    private suspend fun freezeAndObserve(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        transfer: CollaborationSpaceRuntimeTransfer,
    ): AppResult<CollaborationSpaceRuntimeTransferProgress> {
        // The original operation is already durable. A lost response retains that same operation.
        val frozen = transfers.freeze(attempt.owner, transfer)
        attempt.restrict(frozen)
        val afterFreeze = attempt.unchangedAdmission(gate)
        if (afterFreeze is AppResult.Failed) return afterFreeze
        if (frozen is AppResult.Failed) return attempt.failed(frozen.failure)
        if (
            (frozen as AppResult.Success).value !=
                CollaborationSetupReceipt(transfer.legacy.deviceId, 1)
        )
            return attempt.failed(invalidSpaceSetup().failure)
        val observed = transfers.drain(attempt.owner, transfer)
        attempt.restrict(observed)
        val afterDrain = attempt.unchangedAdmission(gate)
        if (afterDrain is AppResult.Failed) return afterDrain
        if (observed is AppResult.Failed) return attempt.failed(observed.failure)
        val drain = (observed as AppResult.Success).value
        if (!validCollaborationSpaceRuntimeDrain(transfer, drain))
            return attempt.failed(invalidSpaceSetup().failure)
        return AppResult.Success(
            CollaborationSpaceRuntimeTransferProgress.AwaitingProvisioning(transfer, drain)
        )
    }

    private fun revalidationRequired() =
        collaborationRecoveryFailure(
            "client.collaboration_revalidation_required",
            FailureKind.Unavailable,
        )

    private fun timeout() =
        collaborationRecoveryFailure("client.collaboration_recovery_timeout", FailureKind.Timeout)
}
