package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlinx.coroutines.*

/**
 * Retires only a confirmed retry observed in current native metadata; never retires gateway work.
 */
class ReconcileCollaborationSpaceRetry(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        commandId: String,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationMessageId(owner.spaceId) || !validCollaborationMessageId(commandId))
            return changedSpaceSetup()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var attempt: CollaborationSpaceSetupAttempt? = null
        return withTimeoutOrNull(15_000) {
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val hard =
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
                    hard,
                )
            attempt = current
            reconcile(current, commandId)
        } ?: (attempt?.failed(timeoutSpaceSetup().failure) ?: timeoutSpaceSetup())
    }

    private suspend fun reconcile(
        attempt: CollaborationSpaceSetupAttempt,
        commandId: String,
    ): AppResult<Boolean> {
        val admission = attempt.admission()
        if (admission is AppResult.Failed) return admission
        val gate = (admission as AppResult.Success).value
        val loaded = commands.find(attempt.access, commandId)
        val afterRead = attempt.current()
        if (afterRead is AppResult.Failed) return afterRead
        if (loaded is AppResult.Failed) return attempt.failed(loaded.failure)
        val record = (loaded as AppResult.Success).value ?: return AppResult.Success(false)
        if (
            !validCollaborationSpaceSetupRecord(attempt.access, record) ||
                record.command.id != commandId
        )
            return attempt.failed(invalidSpaceSetup().failure)
        val outcome =
            record.outcome as? CollaborationSpaceSetupOutcome.Retried
                ?: return AppResult.Success(false)
        if (record.command.binding.nativeSessionId != attempt.owner.parent.nativeSessionId)
            return attempt.failed(changedSpaceSetup().failure)
        val registered =
            registrations.current(attempt.access, record.command.binding.nativeSessionId)
        val afterRegistration = attempt.current()
        if (afterRegistration is AppResult.Failed) return afterRegistration
        if (registered is AppResult.Failed) return attempt.failed(registered.failure)
        val original =
            spaceSetupOriginal(
                attempt.owner,
                record.command.registrationId,
                (registered as AppResult.Success).value,
            )
        if (original is AppResult.Failed) return attempt.failed(original.failure)
        val source = (original as AppResult.Success).value
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        if (
            record.command.request.deviceId != source.receipt.id ||
                record.command.request.deviceVersion < source.receipt.version ||
                record.command.platform != source.command.platform ||
                source.command.platform != platform
        )
            return attempt.failed(changedSpaceSetup().failure)
        val received = devices.status(attempt.owner)
        attempt.restrict(received)
        val afterStatus = attempt.current()
        if (afterStatus is AppResult.Failed) return afterStatus
        if (received is AppResult.Failed) return attempt.failed(received.failure)
        val observed =
            spaceSetupStatus(attempt.owner, source, (received as AppResult.Success).value)
        if (observed is AppResult.Failed) return attempt.failed(observed.failure)
        val beforeArchive = attempt.unchangedAdmission(gate)
        if (beforeArchive is AppResult.Failed) return beforeArchive
        if ((observed as AppResult.Success).value.provisioning.revision < outcome.receipt.version)
            return AppResult.Success(false)
        val archived = commands.archiveRetried(attempt.access, record)
        val finalGate = attempt.unchangedAdmission(gate)
        if (finalGate is AppResult.Failed) return finalGate
        return if (archived is AppResult.Failed) attempt.failed(archived.failure) else archived
    }
}
