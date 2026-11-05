@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Explicit native setup preparation; never overwrites retained work or creates an SDK. */
class PrepareCollaborationSpaceSetup(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val identities: CollaborationSpaceIdentityRepository,
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
        registrationId: String,
        kind: CollaborationSpaceSetupKind,
    ): AppResult<CollaborationSpaceSetupRecord> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationMessageId(owner.spaceId) ||
                !validCollaborationMessageId(registrationId) ||
                build.number !in 1..999_999_999L
        )
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
            prepare(current, registrationId, kind)
        } ?: (attempt?.failed(timeoutSpaceSetup().failure) ?: timeoutSpaceSetup())
    }

    private suspend fun prepare(
        attempt: CollaborationSpaceSetupAttempt,
        registrationId: String,
        kind: CollaborationSpaceSetupKind,
    ): AppResult<CollaborationSpaceSetupRecord> {
        val admission = attempt.admission()
        if (admission is AppResult.Failed) return admission
        val gate = (admission as AppResult.Success).value
        val stored = commands.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterRead = attempt.current()
        if (afterRead is AppResult.Failed) return afterRead
        if (stored is AppResult.Failed) return attempt.failed(stored.failure)
        (stored as AppResult.Success).value?.let {
            return if (
                validCollaborationSpaceSetupCommand(attempt.access, it.command) &&
                    validCollaborationSpaceSetupRecord(attempt.access, it) &&
                    it.command.binding.nativeSessionId == attempt.owner.parent.nativeSessionId
            )
                AppResult.Success(it)
            else attempt.failed(invalidSpaceSetup().failure)
        }
        val source = original(attempt, registrationId)
        if (source is AppResult.Failed) return source
        val original = (source as AppResult.Success).value
        val read = devices.status(attempt.owner)
        attempt.restrict(read)
        val afterStatus = attempt.current()
        if (afterStatus is AppResult.Failed) return afterStatus
        if (read is AppResult.Failed) return attempt.failed(read.failure)
        val status = spaceSetupStatus(attempt.owner, original, (read as AppResult.Success).value)
        if (status is AppResult.Failed) return attempt.failed(status.failure)
        val request =
            spaceSetupRequest(
                original,
                (status as AppResult.Success).value,
                kind,
                Uuid.random().toString(),
                build.number,
                clock.now(),
            )
        if (request is AppResult.Failed) return attempt.failed(request.failure)
        val command =
            CollaborationSpaceSetupCommand(
                registrationId,
                original.command.platform,
                build.number,
                clock.now(),
                (request as AppResult.Success).value,
            )
        if (!validCollaborationSpaceSetupCommand(attempt.access, command))
            return attempt.failed(invalidSpaceSetup().failure)
        return persist(attempt, gate, command)
    }

    private suspend fun original(
        attempt: CollaborationSpaceSetupAttempt,
        registrationId: String,
    ): AppResult<CollaborationSpaceSetupOriginal> {
        val read = registrations.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val after = attempt.current()
        if (after is AppResult.Failed) return after
        if (read is AppResult.Failed) return attempt.failed(read.failure)
        val source =
            spaceSetupOriginal(attempt.owner, registrationId, (read as AppResult.Success).value)
        if (source is AppResult.Failed) return attempt.failed(source.failure)
        val original = (source as AppResult.Success).value
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        if (original.command.platform != platform)
            return attempt.failed(changedSpaceSetup().failure)
        val selected = identities.read(attempt.owner)
        attempt.restrict(selected)
        val afterIdentity = attempt.current()
        if (afterIdentity is AppResult.Failed) return afterIdentity
        if (selected is AppResult.Failed) return attempt.failed(selected.failure)
        val observed = (selected as AppResult.Success).value
        if (observed.owner != attempt.owner || observed.identity != original.command.provider)
            return attempt.failed(changedSpaceSetup().failure)
        return source
    }

    private suspend fun persist(
        attempt: CollaborationSpaceSetupAttempt,
        gate: CollaborationSpaceDirectoryAdmission,
        command: CollaborationSpaceSetupCommand,
    ): AppResult<CollaborationSpaceSetupRecord> {
        val before = attempt.unchangedAdmission(gate)
        if (before is AppResult.Failed) return before
        val inserted = commands.insert(attempt.access, command)
        val after = attempt.unchangedAdmission(gate)
        if (after is AppResult.Failed) return after
        if (inserted is AppResult.Failed) return attempt.failed(inserted.failure)
        when ((inserted as AppResult.Success).value) {
            CollaborationSetupInsertion.STORED,
            CollaborationSetupInsertion.OCCUPIED -> Unit
            CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                return attempt.failed(
                    collaborationRecoveryFailure("client.command_queue_full", FailureKind.Storage)
                        .failure
                )
            CollaborationSetupInsertion.SCOPE_CHANGED ->
                return attempt.failed(
                    collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
                        .failure
                )
        }
        val saved = commands.current(attempt.access, command.binding.nativeSessionId)
        val finalGate = attempt.unchangedAdmission(gate)
        if (finalGate is AppResult.Failed) return finalGate
        if (saved is AppResult.Failed) return attempt.failed(saved.failure)
        val retained =
            (saved as AppResult.Success).value ?: return attempt.failed(changedSpaceSetup().failure)
        return if (
            validCollaborationSpaceSetupRecord(attempt.access, retained) &&
                retained.command.binding.nativeSessionId == command.binding.nativeSessionId
        )
            AppResult.Success(retained)
        else attempt.failed(invalidSpaceSetup().failure)
    }
}

internal fun timeoutSpaceSetup() =
    collaborationRecoveryFailure("client.collaboration_recovery_timeout", FailureKind.Timeout)
