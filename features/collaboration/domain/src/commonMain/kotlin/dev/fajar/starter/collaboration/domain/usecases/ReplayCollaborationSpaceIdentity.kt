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
class ReplayCollaborationSpaceIdentity(
    private val commands: CollaborationSpaceIdentityCommandRepository,
    private val identities: CollaborationSpaceIdentityRepository,
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
    ): AppResult<CollaborationSpaceIdentityCommandRecord> {
        currentCoroutineContext().ensureActive()
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
            val stored = commands.current(access)
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
                !validCollaborationSpaceIdentityRecord(access, original) ||
                    original.command.id != commandId
            )
                return@withTimeoutOrNull conflict()
            // An already confirmed receipt is a local fact, including after reauthentication.
            if (original.outcome is CollaborationSpaceIdentityOutcome.Selected)
                return@withTimeoutOrNull AppResult.Success(original)
            remember((original.outcome as? CollaborationSpaceIdentityOutcome.Unconfirmed)?.failure)
            val admission = directory.admission(access)
            if (admission is AppResult.Failed) return@withTimeoutOrNull failed(admission.failure)
            if (!validCollaborationSpaceDirectoryAdmission((admission as AppResult.Success).value))
                return@withTimeoutOrNull failed(invalid().failure)
            remember(admission.value.denial)
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
                    "Identity selection timed out",
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
    ): AppResult<CollaborationSpaceIdentityCommandRecord> {
        val access = owner.localDirectoryAccess()
        val before = current()
        if (before is AppResult.Failed) return failed(before.failure)
        val admission = directory.admission(access)
        if (admission is AppResult.Failed) return failed(admission.failure)
        if (!validCollaborationSpaceDirectoryAdmission((admission as AppResult.Success).value))
            return failed(invalid().failure)
        remember(admission.value.denial)
        val loaded = commands.current(access)
        val afterRead = current()
        if (afterRead is AppResult.Failed) return failed(afterRead.failure)
        if (loaded is AppResult.Failed) return failed(loaded.failure)
        val original = (loaded as AppResult.Success).value ?: return failed(conflict().failure)
        if (
            original.command.id != commandId ||
                !validCollaborationSpaceIdentityRecord(access, original)
        )
            return failed(conflict().failure)
        if (original.outcome is CollaborationSpaceIdentityOutcome.Selected)
            return AppResult.Success(original)
        // Selection idempotency belongs to account/space/provider realm, not a device or
        // the first client's native family/build. Keep provenance immutable while this
        // explicit attempt uses its freshly captured native owner and actual client headers.
        if (build.number !in 1..999_999_999L)
            return failed(
                collaborationRecoveryFailure(
                        "client.collaboration_invalid_client",
                        FailureKind.Unexpected,
                    )
                    .failure
            )
        if (original.attempts >= 1_000)
            return failed(
                collaborationRecoveryFailure(
                        "client.collaboration_attempt_limit",
                        FailureKind.Conflict,
                    )
                    .failure
            )
        val previousFailure =
            (original.outcome as? CollaborationSpaceIdentityOutcome.Unconfirmed)?.failure
        remember(previousFailure)
        val uncertain = CollaborationSpaceIdentityOutcome.Unconfirmed(previousFailure)
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
        val received = identities.select(owner, original.command)
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
                        retainCollaborationSpaceIdentityFailure(previousFailure, received.failure)
                    )
                is AppResult.Success ->
                    if (received.value.id == owner.parent.accountId && received.value.version == 0L)
                        received
                    else invalid()
            }
        val outcome =
            when (result) {
                is AppResult.Failed -> CollaborationSpaceIdentityOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> CollaborationSpaceIdentityOutcome.Selected(result.value)
            }
        val saved = commands.update(access, sending, outcome, sending.attempts)
        val afterSave = current()
        if (afterSave is AppResult.Failed) return failed(afterSave.failure)
        if (result is AppResult.Failed) return result
        if (saved is AppResult.Failed) return failed(saved.failure)
        if (!(saved as AppResult.Success).value) return failed(conflict().failure)
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
