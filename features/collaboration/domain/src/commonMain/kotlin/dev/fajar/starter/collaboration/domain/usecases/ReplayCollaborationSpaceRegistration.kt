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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit serialized attempt; no retry scheduler or SDK/provider device creation. */
class ReplayCollaborationSpaceRegistration(
    private val commands: CollaborationSpaceRegistrationCommandRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val local: CollaborationLocalDeviceIdentityRepository,
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
        originalNativeSessionId: String,
        commandId: String,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationMessageId(owner.spaceId) ||
                !validCollaborationMessageId(originalNativeSessionId) ||
                !validCollaborationMessageId(commandId)
        )
            return conflict()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val access = owner.localDirectoryAccess()
        var denial: Failure? = null
        fun remember(value: Failure?) {
            if (value != null && collaborationSpaceDirectoryResponseClosesRead(value))
                denial = value
        }
        fun failed(value: Failure) = AppResult.Failed(denial ?: value)
        return withTimeoutOrNull(15_000) {
            val local = sessions.current()
            currentCoroutineContext().ensureActive()
            if (local is AppResult.Failed) return@withTimeoutOrNull local
            val localSession = (local as AppResult.Success).value
            val owned = localCollaborationSpaceAccess(access, localSession, server.audience)
            if (owned is AppResult.Failed) return@withTimeoutOrNull owned
            val stored = commands.current(access, originalNativeSessionId)
            currentCoroutineContext().ensureActive()
            val afterRead = sessions.current()
            if (afterRead is AppResult.Failed) return@withTimeoutOrNull afterRead
            val stillOwned =
                localCollaborationSpaceAccess(
                    access,
                    (afterRead as AppResult.Success).value,
                    server.audience,
                )
            if (stillOwned is AppResult.Failed) return@withTimeoutOrNull stillOwned
            if (stored is AppResult.Failed) return@withTimeoutOrNull stored
            val original =
                (stored as AppResult.Success).value ?: return@withTimeoutOrNull conflict()
            if (
                !validCollaborationSpaceRegistrationRecord(access, original) ||
                    original.command.id != commandId ||
                    original.command.binding.nativeSessionId != originalNativeSessionId
            )
                return@withTimeoutOrNull conflict()
            // An already confirmed receipt is a local fact, including after reauthentication.
            if (original.outcome is CollaborationSpaceRegistrationOutcome.Registered)
                return@withTimeoutOrNull AppResult.Success(original)
            remember(
                (original.outcome as? CollaborationSpaceRegistrationOutcome.Unconfirmed)?.failure
            )
            val admission = directory.admission(access)
            if (admission is AppResult.Failed) return@withTimeoutOrNull failed(admission.failure)
            if (!validCollaborationSpaceDirectoryAdmission((admission as AppResult.Success).value))
                return@withTimeoutOrNull failed(invalid().failure)
            remember(admission.value.denial)
            if (original.command.binding.nativeSessionId != owner.parent.nativeSessionId)
                return@withTimeoutOrNull failed(
                    collaborationRecoveryFailure(
                            "client.collaboration_original_device_required",
                            FailureKind.Conflict,
                        )
                        .failure
                )
            val initial =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (initial is AppResult.Failed) return@withTimeoutOrNull failed(initial.failure)
            val deadline =
                Instant.fromEpochMilliseconds(
                    minOf(
                        requireNotNull(localSession).binding.expiresAtEpochMillis,
                        (initial as AppResult.Success).value.binding.expiresAtEpochMillis,
                    )
                )
            suspend fun current(): AppResult<Unit> {
                currentCoroutineContext().ensureActive()
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
            execution.withLock { deliver(owner, commandId, ::current, ::failed, ::remember) }
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Device registration timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    private suspend fun deliver(
        owner: CollaborationSpaceOwner,
        commandId: String,
        current: suspend () -> AppResult<Unit>,
        failed: (Failure) -> AppResult.Failed,
        remember: (Failure?) -> Unit,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        val access = owner.localDirectoryAccess()
        val before = current()
        if (before is AppResult.Failed) return failed(before.failure)
        val admission = directory.admission(access)
        if (admission is AppResult.Failed) return failed(admission.failure)
        if (!validCollaborationSpaceDirectoryAdmission((admission as AppResult.Success).value))
            return failed(invalid().failure)
        remember(admission.value.denial)
        val loaded = commands.current(access, owner.parent.nativeSessionId)
        val afterRead = current()
        if (afterRead is AppResult.Failed) return failed(afterRead.failure)
        if (loaded is AppResult.Failed) return failed(loaded.failure)
        val original = (loaded as AppResult.Success).value ?: return failed(conflict().failure)
        if (
            original.command.id != commandId ||
                !validCollaborationSpaceRegistrationRecord(access, original)
        )
            return failed(conflict().failure)
        if (original.outcome is CollaborationSpaceRegistrationOutcome.Registered)
            return AppResult.Success(original)
        remember((original.outcome as? CollaborationSpaceRegistrationOutcome.Unconfirmed)?.failure)
        val originalReady = checkOriginal(owner, original, current, failed)
        if (originalReady is AppResult.Failed) return originalReady
        // An uncertain original may already be provisioning/frozen; never create a new operation
        // merely because the original open SDK is no longer available.
        val beforeMark = directory.admission(access)
        val afterGate = current()
        if (afterGate is AppResult.Failed) return failed(afterGate.failure)
        if (beforeMark is AppResult.Failed) return failed(beforeMark.failure)
        val gate = (beforeMark as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(gate)) return failed(invalid().failure)
        if (gate.denial != null && gate.revision != admission.value.revision) {
            remember(gate.denial)
            return failed(gate.denial)
        }
        return markAndSend(owner, original, gate, current, failed, remember)
    }

    private suspend fun checkOriginal(
        owner: CollaborationSpaceOwner,
        original: CollaborationSpaceRegistrationRecord,
        current: suspend () -> AppResult<Unit>,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<Unit> {
        val command = original.command
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        if (
            build.number !in 1..999_999_999L ||
                command.platform != platform ||
                command.binding.nativeSessionId != owner.parent.nativeSessionId
        )
            return failed(
                collaborationRecoveryFailure(
                        "client.collaboration_original_device_required",
                        FailureKind.Conflict,
                    )
                    .failure
            )
        // Only proven original-device enrollment is enabled until SPACE inventory/recovery exists.
        val legacy = command.legacy
        if (
            command.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
                legacy == null ||
                legacy.binding.nativeSessionId != owner.parent.nativeSessionId
        )
            return failed(
                collaborationRecoveryFailure(
                        "client.collaboration_original_device_required",
                        FailureKind.Conflict,
                    )
                    .failure
            )
        if (original.outcome == CollaborationSpaceRegistrationOutcome.Pending) {
            val sourceOwner =
                CollaborationOwner(
                    owner.parent.sessionId,
                    legacy.binding.audience,
                    legacy.binding.accountId,
                    legacy.binding.companyId,
                    legacy.binding.nativeSessionId,
                )
            val actual = local.readLocalIdentity(sourceOwner, legacy.deviceId)
            val afterLocal = current()
            if (afterLocal is AppResult.Failed) return failed(afterLocal.failure)
            if (actual is AppResult.Failed) return failed(actual.failure)
            if ((actual as AppResult.Success).value != legacy.identity)
                return failed(
                    collaborationRecoveryFailure(
                            "client.collaboration_original_device_required",
                            FailureKind.Conflict,
                        )
                        .failure
                )
        }
        return AppResult.Success(Unit)
    }

    private suspend fun markAndSend(
        owner: CollaborationSpaceOwner,
        original: CollaborationSpaceRegistrationRecord,
        gate: CollaborationSpaceDirectoryAdmission,
        current: suspend () -> AppResult<Unit>,
        failed: (Failure) -> AppResult.Failed,
        remember: (Failure?) -> Unit,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        val access = owner.localDirectoryAccess()
        if (original.attempts >= 1_000)
            return failed(
                collaborationRecoveryFailure(
                        "client.collaboration_attempt_limit",
                        FailureKind.Conflict,
                    )
                    .failure
            )
        val previousFailure =
            (original.outcome as? CollaborationSpaceRegistrationOutcome.Unconfirmed)?.failure
        remember(previousFailure)
        val uncertain = CollaborationSpaceRegistrationOutcome.Unconfirmed(previousFailure)
        val sending =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = uncertain,
            )
        val marked = commands.update(access, original, uncertain, sending.attempts)
        val afterMark = current()
        if (afterMark is AppResult.Failed) return failed(afterMark.failure)
        if (marked is AppResult.Failed) return failed(marked.failure)
        if (!(marked as AppResult.Success).value) return failed(conflict().failure)
        return send(owner, sending, previousFailure, gate, current, failed, remember)
    }

    private suspend fun send(
        owner: CollaborationSpaceOwner,
        sending: CollaborationSpaceRegistrationRecord,
        previousFailure: Failure?,
        gate: CollaborationSpaceDirectoryAdmission,
        current: suspend () -> AppResult<Unit>,
        failed: (Failure) -> AppResult.Failed,
        remember: (Failure?) -> Unit,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        val access = owner.localDirectoryAccess()
        val received = devices.register(owner, sending.command)
        if (
            received is AppResult.Failed &&
                collaborationSpaceDirectoryResponseClosesRead(received.failure)
        ) {
            remember(received.failure)
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { directory.restrict(access, received.failure) }
            }
        }
        currentCoroutineContext().ensureActive()
        val afterSend = current()
        if (afterSend is AppResult.Failed) return failed(afterSend.failure)
        val result =
            when (received) {
                is AppResult.Failed ->
                    failed(
                        retainCollaborationSpaceRegistrationFailure(
                            previousFailure,
                            received.failure,
                        )
                    )
                is AppResult.Success ->
                    if (
                        validCollaborationMessageId(received.value.id) &&
                            received.value.version in 0..999_999
                    )
                        received
                    else failed(invalid().failure)
            }
        val outcome =
            when (result) {
                is AppResult.Failed ->
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(result.failure)
                is AppResult.Success ->
                    CollaborationSpaceRegistrationOutcome.Registered(result.value)
            }
        val saved = commands.update(access, sending, outcome, sending.attempts)
        val afterSave = current()
        if (afterSave is AppResult.Failed) return failed(afterSave.failure)
        if (result is AppResult.Failed) return result
        if (saved is AppResult.Failed) return failed(saved.failure)
        if (!(saved as AppResult.Success).value) return failed(conflict().failure)
        val finalGate = directory.admission(access)
        val afterFinalGate = current()
        if (afterFinalGate is AppResult.Failed) return failed(afterFinalGate.failure)
        if (finalGate is AppResult.Failed) return failed(finalGate.failure)
        val latest = (finalGate as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(latest)) return failed(invalid().failure)
        if (latest.denial != null && latest.revision != gate.revision) {
            remember(latest.denial)
            // The received receipt remains an owned fact; no current SDK authority is published.
            return failed(latest.denial)
        }
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private fun conflict() =
        collaborationRecoveryFailure("client.collaboration_command_changed", FailureKind.Conflict)

    private fun invalid() =
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
