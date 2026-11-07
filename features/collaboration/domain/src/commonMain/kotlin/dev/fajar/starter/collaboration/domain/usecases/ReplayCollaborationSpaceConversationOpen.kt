package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** One explicit original Open attempt; canonical local facts precede exact archival. */
class ReplayCollaborationSpaceConversationOpen(
    private val commands: CollaborationSpaceConversationCommandRepository,
    private val conversations: CollaborationSpaceConversationRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
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
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        currentCoroutineContext().ensureActive()
        val access = owner.localDirectoryAccess()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var denial: Failure? = null
        fun remember(failure: Failure?) {
            if (failure != null && collaborationSpaceDirectoryResponseClosesRead(failure))
                denial = retainCollaborationSpaceConversationFailure(denial, failure)
        }
        fun failed(failure: Failure) =
            AppResult.Failed(retainCollaborationSpaceConversationFailure(denial, failure))
        suspend fun native(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            currentCoroutineContext().ensureActive()
            if (captured is AppResult.Failed) return captured
            val deadline =
                Instant.fromEpochMilliseconds(
                    (captured as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            nativeDeadline = nativeDeadline?.let { minOf(it, deadline) } ?: deadline
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= requireNotNull(nativeDeadline) ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >=
                        minOf(15.seconds, requireNotNull(nativeDeadline) - requestedAt)
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_revalidation_required",
                    FailureKind.Unavailable,
                )
            return AppResult.Success(Unit)
        }
        return withTimeoutOrNull(15_000) {
            val initial = owned(access)
            if (initial is AppResult.Failed) return@withTimeoutOrNull initial
            if (!validCollaborationSpaceConversationId(commandId))
                return@withTimeoutOrNull invalid()
            val found = commands.find(access, commandId)
            val afterRead = owned(access)
            if (afterRead is AppResult.Failed) return@withTimeoutOrNull afterRead
            if (found is AppResult.Failed) return@withTimeoutOrNull found
            val original = (found as AppResult.Success).value ?: return@withTimeoutOrNull conflict()
            if (
                original.command.id != commandId ||
                    original.command !is CollaborationSpaceConversationCommand.Open ||
                    !validCollaborationSpaceConversationCommandRecord(access, original)
            )
                return@withTimeoutOrNull invalid()
            remember(
                (original.outcome as? CollaborationSpaceConversationOutcome.Unconfirmed)?.failure
            )
            val recorded =
                if (original.outcome is CollaborationSpaceConversationOutcome.Recorded)
                    AppResult.Success(original)
                else dispatch(owner, original, ::native, ::remember, ::failed)
            if (recorded is AppResult.Failed) return@withTimeoutOrNull recorded
            materialize(
                owner,
                (recorded as AppResult.Success).value,
                ::native,
                ::remember,
                ::failed,
            )
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Conversation recovery timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    private suspend fun dispatch(
        owner: CollaborationSpaceOwner,
        original: CollaborationSpaceConversationCommandRecord,
        native: suspend () -> AppResult<Unit>,
        remember: (Failure?) -> Unit,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val access = owner.localDirectoryAccess()
        val gate = directory.admission(access)
        val afterGate = owned(access)
        if (afterGate is AppResult.Failed) return afterGate
        if (gate is AppResult.Failed) return failed(gate.failure)
        if (!validCollaborationSpaceDirectoryAdmission((gate as AppResult.Success).value))
            return failed(invalid().failure)
        remember(gate.value.denial)
        val beforeMark = native()
        if (beforeMark is AppResult.Failed) return failed(beforeMark.failure)
        if (original.attempts >= 1_000 || original.revision > 9_007_199_254_740_989L)
            return failed(
                Failure(
                    FailureKind.Conflict,
                    "Conversation attempt limit reached",
                    code = "client.collaboration_attempt_limit",
                )
            )
        val retainedFailure =
            (original.outcome as? CollaborationSpaceConversationOutcome.Unconfirmed)?.failure
        val previous =
            gate.value.denial?.let {
                retainCollaborationSpaceConversationFailure(retainedFailure, it)
            } ?: retainedFailure
        val proposed =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = CollaborationSpaceConversationOutcome.Unconfirmed(previous),
            )
        val marked = commands.update(access, original, proposed.outcome, proposed.attempts)
        val afterMark = owned(access)
        if (afterMark is AppResult.Failed) return afterMark
        if (marked is AppResult.Failed) return failed(marked.failure)
        if (!(marked as AppResult.Success).value) return failed(conflict().failure)
        // Error presentation text is not durable identity. Use the actual stored record for CAS.
        val retained = commands.find(access, original.command.id)
        val afterRead = owned(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (retained is AppResult.Failed) return failed(retained.failure)
        val sending = (retained as AppResult.Success).value
        val storedOutcome = sending?.outcome as? CollaborationSpaceConversationOutcome.Unconfirmed
        if (
            sending == null ||
                storedOutcome == null ||
                sending.command != original.command ||
                sending.revision != proposed.revision ||
                sending.attempts != proposed.attempts ||
                !validCollaborationSpaceConversationCommandRecord(access, sending) ||
                storedOutcome.failure?.kind != previous?.kind ||
                storedOutcome.failure?.code != previous?.code ||
                storedOutcome.failure?.retryAfterSeconds != previous?.retryAfterSeconds
        )
            return failed(conflict().failure)
        val beforeSend = native()
        if (beforeSend is AppResult.Failed) return failed(beforeSend.failure)
        val received =
            conversations.open(owner, original.command.id, original.command.peerAccountId)
        return recordResponse(access, sending, storedOutcome.failure, received, remember, failed)
    }

    /**
     * Captured responses have a bounded persistence lifetime independent of caller cancellation.
     */
    private suspend fun recordResponse(
        access: CollaborationSpaceLocalAccess,
        sending: CollaborationSpaceConversationCommandRecord,
        previous: Failure?,
        received: AppResult<CollaborationSetupReceipt>,
        remember: (Failure?) -> Unit,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val result =
            when (received) {
                is AppResult.Failed -> {
                    remember(received.failure)
                    failed(retainCollaborationSpaceConversationFailure(previous, received.failure))
                }
                is AppResult.Success ->
                    if (
                        validCollaborationSpaceConversationId(received.value.id) &&
                            received.value.version == 0L
                    )
                        received
                    else failed(invalid().failure)
            }
        val outcome =
            when (result) {
                is AppResult.Failed ->
                    CollaborationSpaceConversationOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> CollaborationSpaceConversationOutcome.Recorded(result.value)
            }
        val saved: AppResult<Boolean> =
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) {
                    val failure = (received as? AppResult.Failed)?.failure
                    if (
                        failure != null &&
                            collaborationSpaceConversationResponseClosesDirectory(failure)
                    )
                        directory.restrict(access, failure)
                    val current = owned(access)
                    if (current is AppResult.Failed) current
                    else commands.update(access, sending, outcome, sending.attempts)
                }
                    ?: collaborationRecoveryFailure(
                        "client.collaboration_recovery_timeout",
                        FailureKind.Timeout,
                    )
            }
        currentCoroutineContext().ensureActive()
        val afterCapture = owned(access)
        if (afterCapture is AppResult.Failed) return afterCapture
        if (result is AppResult.Failed) return result
        if (saved is AppResult.Success && saved.value)
            return AppResult.Success(
                sending.copy(revision = sending.revision + 1, outcome = outcome)
            )
        // Only an exact retained positive result resolves an interrupted outcome write.
        val retained = commands.find(access, sending.command.id)
        val afterRecovery = owned(access)
        if (afterRecovery is AppResult.Failed) return afterRecovery
        if (retained is AppResult.Success) {
            val record = retained.value
            if (
                record != null &&
                    record.command == sending.command &&
                    record.outcome == outcome &&
                    validCollaborationSpaceConversationCommandRecord(access, record)
            )
                return AppResult.Success(record)
        }
        return when {
            saved is AppResult.Failed -> failed(saved.failure)
            retained is AppResult.Failed -> failed(retained.failure)
            else -> failed(conflict().failure)
        }
    }

    private suspend fun materialize(
        owner: CollaborationSpaceOwner,
        record: CollaborationSpaceConversationCommandRecord,
        native: suspend () -> AppResult<Unit>,
        remember: (Failure?) -> Unit,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val access = owner.localDirectoryAccess()
        val receipt =
            (record.outcome as? CollaborationSpaceConversationOutcome.Recorded)?.receipt
                ?: return failed(invalid().failure)
        val local = metadata.getById(access, receipt.id)
        val afterLocal = owned(access)
        if (afterLocal is AppResult.Failed) return afterLocal
        if (local is AppResult.Failed) return failed(local.failure)
        (local as AppResult.Success).value?.let {
            return if (matchesRecordedCollaborationSpaceConversation(access, record, it))
                archive(access, record, failed)
            else failed(invalid().failure)
        }
        val published = acquireAndPublish(owner, record, native, remember, failed)
        if (published is AppResult.Failed) return published
        return archive(access, record, failed)
    }

    private suspend fun acquireAndPublish(
        owner: CollaborationSpaceOwner,
        record: CollaborationSpaceConversationCommandRecord,
        native: suspend () -> AppResult<Unit>,
        remember: (Failure?) -> Unit,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<Unit> {
        val access = owner.localDirectoryAccess()
        val receipt =
            (record.outcome as? CollaborationSpaceConversationOutcome.Recorded)?.receipt
                ?: return failed(invalid().failure)
        val admission = directory.admission(access)
        val afterAdmission = owned(access)
        if (afterAdmission is AppResult.Failed) return afterAdmission
        if (admission is AppResult.Failed) return failed(admission.failure)
        if (!validCollaborationSpaceDirectoryAdmission((admission as AppResult.Success).value))
            return failed(invalid().failure)
        remember(admission.value.denial)
        val beforeRead = native()
        if (beforeRead is AppResult.Failed) return failed(beforeRead.failure)
        val received = conversations.get(owner, receipt.id)
        if (received is AppResult.Failed) {
            remember(received.failure)
            if (collaborationSpaceConversationResponseClosesDirectory(received.failure))
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { directory.restrict(access, received.failure) }
                }
        }
        currentCoroutineContext().ensureActive()
        val afterRead = owned(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (received is AppResult.Failed) return failed(received.failure)
        val value = (received as AppResult.Success).value
        if (
            !validCollaborationSpaceConversation(value, owner, receipt.id) ||
                !matchesRecordedCollaborationSpaceConversation(
                    access,
                    record,
                    value.localMetadata(),
                )
        )
            return failed(invalid().failure)
        val stored = metadata.retain(access, value)
        val afterStore = owned(access)
        if (afterStore is AppResult.Failed) return afterStore
        val retained = metadata.getById(access, receipt.id)
        val afterRecovery = owned(access)
        if (afterRecovery is AppResult.Failed) return afterRecovery
        if (retained is AppResult.Success && retained.value == value.localMetadata())
            return AppResult.Success(Unit)
        return when {
            stored is AppResult.Failed -> failed(stored.failure)
            retained is AppResult.Failed -> failed(retained.failure)
            else -> failed(conflict().failure)
        }
    }

    private suspend fun archive(
        access: CollaborationSpaceLocalAccess,
        record: CollaborationSpaceConversationCommandRecord,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val archived = commands.archiveRecorded(access, record)
        val after = owned(access)
        if (after is AppResult.Failed) return after
        return when (archived) {
            is AppResult.Failed -> failed(archived.failure)
            is AppResult.Success ->
                if (archived.value) AppResult.Success(record) else failed(conflict().failure)
        }
    }

    private suspend fun owned(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceLocalAccess> {
        currentCoroutineContext().ensureActive()
        val session = sessions.current()
        currentCoroutineContext().ensureActive()
        return when (session) {
            is AppResult.Failed -> session
            is AppResult.Success -> {
                currentCoroutineContext().ensureActive()
                localCollaborationSpaceAccess(access, session.value, server.audience)
            }
        }
    }

    private fun conflict() =
        collaborationRecoveryFailure("client.collaboration_command_changed", FailureKind.Conflict)

    private fun invalid() =
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
