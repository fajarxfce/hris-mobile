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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit attempt over durable original work. Never acquires, freezes or replaces an SDK. */
class ReplayCollaborationSpaceSetup(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val identities: CollaborationSpaceIdentityRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val setup: CollaborationSpaceSetupRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        commandId: String,
    ): AppResult<CollaborationSpaceSetupRecord> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationMessageId(owner.spaceId) || !validCollaborationMessageId(commandId))
            return changedSpaceSetup()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val access = owner.localDirectoryAccess()
        var denial: Failure? = null
        var attempt: CollaborationSpaceSetupAttempt? = null
        fun remember(value: Failure?) {
            if (value != null && collaborationSpaceDirectoryResponseClosesRead(value))
                denial = value
        }
        fun failed(value: Failure) = attempt?.failed(value) ?: AppResult.Failed(denial ?: value)
        return withTimeoutOrNull(15_000) {
            val local = sessions.current()
            currentCoroutineContext().ensureActive()
            if (local is AppResult.Failed) return@withTimeoutOrNull local
            val session = (local as AppResult.Success).value
            val owned = localCollaborationSpaceAccess(access, session, server.audience)
            if (owned is AppResult.Failed) return@withTimeoutOrNull owned
            val loaded = commands.find(access, commandId)
            currentCoroutineContext().ensureActive()
            val after = sessions.current()
            if (after is AppResult.Failed) return@withTimeoutOrNull after
            val stillOwned =
                localCollaborationSpaceAccess(
                    access,
                    (after as AppResult.Success).value,
                    server.audience,
                )
            if (stillOwned is AppResult.Failed) return@withTimeoutOrNull stillOwned
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val original =
                (loaded as AppResult.Success).value ?: return@withTimeoutOrNull changedSpaceSetup()
            if (
                original.command.id != commandId ||
                    !validCollaborationSpaceSetupRecord(access, original)
            )
                return@withTimeoutOrNull invalidSpaceSetup()
            // A received result (including an archive) remains owned across age/build/parent
            // changes.
            if (original.completed()) return@withTimeoutOrNull AppResult.Success(original)
            remember((original.outcome as? CollaborationSpaceSetupOutcome.Unconfirmed)?.failure)
            val admission = directory.admission(access)
            if (admission is AppResult.Failed) return@withTimeoutOrNull failed(admission.failure)
            if (!validCollaborationSpaceDirectoryAdmission((admission as AppResult.Success).value))
                return@withTimeoutOrNull failed(invalidSpaceSetup().failure)
            remember(admission.value.denial)
            if (original.command.binding.nativeSessionId != owner.parent.nativeSessionId)
                return@withTimeoutOrNull failed(originalSpaceSetupRequired().failure)
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull failed(captured.failure)
            val hard =
                Instant.fromEpochMilliseconds(
                    minOf(
                        requireNotNull(session).binding.expiresAtEpochMillis,
                        (captured as AppResult.Success).value.binding.expiresAtEpochMillis,
                    )
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
            current.remember(denial)
            attempt = current
            execution.withLock { deliver(current, commandId) }
        } ?: failed(timeoutSpaceSetup().failure)
    }

    private suspend fun deliver(
        attempt: CollaborationSpaceSetupAttempt,
        commandId: String,
    ): AppResult<CollaborationSpaceSetupRecord> {
        val admission = attempt.admission()
        if (admission is AppResult.Failed) return admission
        val gate = (admission as AppResult.Success).value
        val loaded = commands.find(attempt.access, commandId)
        val afterRead = attempt.current()
        if (afterRead is AppResult.Failed) return afterRead
        if (loaded is AppResult.Failed) return attempt.failed(loaded.failure)
        val original =
            (loaded as AppResult.Success).value
                ?: return attempt.failed(changedSpaceSetup().failure)
        if (
            original.command.id != commandId ||
                !validCollaborationSpaceSetupRecord(attempt.access, original)
        )
            return attempt.failed(invalidSpaceSetup().failure)
        if (original.completed()) return AppResult.Success(original)
        attempt.remember((original.outcome as? CollaborationSpaceSetupOutcome.Unconfirmed)?.failure)
        val source = original(attempt, original.command)
        if (source is AppResult.Failed) return source
        if (original.outcome is CollaborationSpaceSetupOutcome.Pending) {
            val ready = pending(attempt, original.command, (source as AppResult.Success).value)
            if (ready is AppResult.Failed) return ready
        }
        val beforeMark = attempt.unchangedAdmission(gate)
        if (beforeMark is AppResult.Failed) return beforeMark
        return markAndSend(attempt, original, gate)
    }

    private suspend fun original(
        attempt: CollaborationSpaceSetupAttempt,
        command: CollaborationSpaceSetupCommand,
    ): AppResult<CollaborationSpaceSetupOriginal> {
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        if (
            command.binding.nativeSessionId != attempt.owner.parent.nativeSessionId ||
                command.platform != platform ||
                build.number !in 1..999_999_999L
        )
            return attempt.failed(originalSpaceSetupRequired().failure)
        if (command.request is CollaborationSpaceBootstrap && command.clientBuild != build.number)
            return attempt.failed(
                collaborationRecoveryFailure(
                        "client.collaboration_build_changed",
                        FailureKind.Conflict,
                    )
                    .failure
            )
        val read = registrations.current(attempt.access, command.binding.nativeSessionId)
        val after = attempt.current()
        if (after is AppResult.Failed) return after
        if (read is AppResult.Failed) return attempt.failed(read.failure)
        val source =
            spaceSetupOriginal(
                attempt.owner,
                command.registrationId,
                (read as AppResult.Success).value,
            )
        if (source is AppResult.Failed) return attempt.failed(source.failure)
        val original = (source as AppResult.Success).value
        if (
            command.request.deviceId != original.receipt.id ||
                command.request.deviceVersion < original.receipt.version ||
                command.platform != original.command.platform
        )
            return attempt.failed(changedSpaceSetup().failure)
        val bootstrap = command.request as? CollaborationSpaceBootstrap
        if (
            bootstrap != null &&
                (bootstrap.provider != original.command.provider ||
                    bootstrap.matrixDeviceId != original.command.legacy?.identity?.matrixDeviceId)
        )
            return attempt.failed(originalSpaceSetupRequired().failure)
        return source
    }

    private suspend fun pending(
        attempt: CollaborationSpaceSetupAttempt,
        command: CollaborationSpaceSetupCommand,
        original: CollaborationSpaceSetupOriginal,
    ): AppResult<Unit> {
        val identity = identities.read(attempt.owner)
        attempt.restrict(identity)
        val afterIdentity = attempt.current()
        if (afterIdentity is AppResult.Failed) return afterIdentity
        if (identity is AppResult.Failed) return attempt.failed(identity.failure)
        val provider = (identity as AppResult.Success).value
        if (provider.owner != attempt.owner || provider.identity != original.command.provider)
            return attempt.failed(changedSpaceSetup().failure)
        val read = devices.status(attempt.owner)
        attempt.restrict(read)
        val afterStatus = attempt.current()
        if (afterStatus is AppResult.Failed) return afterStatus
        if (read is AppResult.Failed) return attempt.failed(read.failure)
        val status = spaceSetupStatus(attempt.owner, original, (read as AppResult.Success).value)
        if (status is AppResult.Failed) return attempt.failed(status.failure)
        val kind =
            when (command.request) {
                is CollaborationSpaceProvisioningRetry -> CollaborationSpaceSetupKind.RETRY
                is CollaborationSpaceBootstrap -> CollaborationSpaceSetupKind.BOOTSTRAP
            }
        val request =
            spaceSetupRequest(
                original,
                (status as AppResult.Success).value,
                kind,
                command.id,
                build.number,
                clock.now(),
            )
        if (request is AppResult.Failed) return attempt.failed(request.failure)
        return if ((request as AppResult.Success).value == command.request) AppResult.Success(Unit)
        else attempt.failed(changedSpaceSetup().failure)
    }

    private suspend fun markAndSend(
        attempt: CollaborationSpaceSetupAttempt,
        original: CollaborationSpaceSetupRecord,
        gate: CollaborationSpaceDirectoryAdmission,
    ): AppResult<CollaborationSpaceSetupRecord> {
        if (original.attempts >= 1_000)
            return attempt.failed(
                collaborationRecoveryFailure(
                        "client.collaboration_attempt_limit",
                        FailureKind.Conflict,
                    )
                    .failure
            )
        val failure = (original.outcome as? CollaborationSpaceSetupOutcome.Unconfirmed)?.failure
        val uncertain = CollaborationSpaceSetupOutcome.Unconfirmed(failure)
        val sending =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = uncertain,
            )
        val saved = commands.update(attempt.access, original, uncertain, sending.attempts)
        val after = attempt.unchangedAdmission(gate)
        if (after is AppResult.Failed) return after
        if (saved is AppResult.Failed) return attempt.failed(saved.failure)
        if (!(saved as AppResult.Success).value) return attempt.failed(changedSpaceSetup().failure)
        return send(attempt, sending, gate)
    }

    private suspend fun send(
        attempt: CollaborationSpaceSetupAttempt,
        sending: CollaborationSpaceSetupRecord,
        gate: CollaborationSpaceDirectoryAdmission,
    ): AppResult<CollaborationSpaceSetupRecord> {
        val received = request(attempt, sending.command)
        attempt.restrict(received)
        val afterSend = attempt.current()
        if (afterSend is AppResult.Failed) return afterSend
        val result =
            when (received) {
                is AppResult.Failed -> attempt.failed(received.failure)
                is AppResult.Success -> received
            }
        val outcome =
            when (result) {
                is AppResult.Failed -> CollaborationSpaceSetupOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> result.value
            }
        val saved = commands.update(attempt.access, sending, outcome, sending.attempts)
        val afterSave = attempt.current()
        if (afterSave is AppResult.Failed) return afterSave
        if (result is AppResult.Failed) return result
        if (saved is AppResult.Failed) return attempt.failed(saved.failure)
        if (!(saved as AppResult.Success).value) return attempt.failed(changedSpaceSetup().failure)
        val finalGate = attempt.unchangedAdmission(gate)
        if (finalGate is AppResult.Failed) return finalGate
        if (
            outcome is CollaborationSpaceSetupOutcome.Bootstrapped &&
                outcome.gateway.accessExpiresAtEpochMillis <= clock.now().toEpochMilliseconds()
        )
            return attempt.failed(
                collaborationRecoveryFailure(
                        "client.collaboration_revalidation_required",
                        FailureKind.Unavailable,
                    )
                    .failure
            )
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private suspend fun request(
        attempt: CollaborationSpaceSetupAttempt,
        command: CollaborationSpaceSetupCommand,
    ): AppResult<CollaborationSpaceSetupOutcome> =
        when (val input = command.request) {
            is CollaborationSpaceProvisioningRetry ->
                when (val result = setup.retry(attempt.owner, input)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        if (
                            result.value.id == input.deviceId &&
                                result.value.version == input.provisioningRevision + 1
                        )
                            AppResult.Success(CollaborationSpaceSetupOutcome.Retried(result.value))
                        else invalidSpaceSetup()
                }
            is CollaborationSpaceBootstrap ->
                when (val result = setup.bootstrap(attempt.owner, input)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        when {
                            !validCollaborationSpaceSetupGateway(command, result.value) ||
                                result.value.expiresAtEpochMillis >
                                    attempt.hardDeadline.toEpochMilliseconds() ->
                                invalidSpaceSetup()
                            result.value.accessExpiresAtEpochMillis <=
                                clock.now().toEpochMilliseconds() ->
                                collaborationRecoveryFailure(
                                    "client.collaboration_revalidation_required",
                                    FailureKind.Unavailable,
                                )
                            else ->
                                AppResult.Success(
                                    CollaborationSpaceSetupOutcome.Bootstrapped(result.value)
                                )
                        }
                }
        }
}

private fun CollaborationSpaceSetupRecord.completed() =
    outcome is CollaborationSpaceSetupOutcome.Retried ||
        outcome is CollaborationSpaceSetupOutcome.Bootstrapped

private fun originalSpaceSetupRequired() =
    collaborationRecoveryFailure(
        "client.collaboration_original_device_required",
        FailureKind.Conflict,
    )
