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

/**
 * One explicit original Room request; durable receipts never authorize SDK delivery or replacement.
 */
class ReplayCollaborationSpaceRoomRequest(
    private val commands: CollaborationSpaceConversationCommandRepository,
    private val rooms: CollaborationSpaceRoomRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        conversation: CollaborationSpaceConversation,
        commandId: String,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        currentCoroutineContext().ensureActive()
        val owner = conversation.owner
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
                    !validCollaborationSpaceConversation(conversation) ||
                    !validCollaborationSpaceRoomCommand(conversation, original)
            )
                return@withTimeoutOrNull invalid()
            remember(
                (original.outcome as? CollaborationSpaceConversationOutcome.Unconfirmed)?.failure
            )
            when (original.outcome) {
                is CollaborationSpaceConversationOutcome.Recorded,
                is CollaborationSpaceConversationOutcome.Fenced -> AppResult.Success(original)
                is CollaborationSpaceConversationOutcome.Unconfirmed,
                CollaborationSpaceConversationOutcome.Pending ->
                    dispatchOriginal(conversation, original, ::native, ::remember, ::failed)
            }
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Room request timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    private suspend fun dispatchOriginal(
        conversation: CollaborationSpaceConversation,
        original: CollaborationSpaceConversationCommandRecord,
        native: suspend () -> AppResult<Unit>,
        remember: (Failure?) -> Unit,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val access = conversation.owner.localDirectoryAccess()
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
                    "Room request attempt limit reached",
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
        val command = original.command as CollaborationSpaceConversationCommand.Room
        val received = rooms.request(conversation, command.id, command.intent.request)
        return recordResponse(
            conversation,
            sending,
            storedOutcome.failure,
            received,
            remember,
            failed,
        )
    }

    /**
     * Captured responses have a bounded persistence lifetime independent of caller cancellation.
     */
    private suspend fun recordResponse(
        conversation: CollaborationSpaceConversation,
        sending: CollaborationSpaceConversationCommandRecord,
        previous: Failure?,
        received: AppResult<CollaborationSetupReceipt>,
        remember: (Failure?) -> Unit,
        failed: (Failure) -> AppResult.Failed,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val access = conversation.owner.localDirectoryAccess()
        val result: AppResult<CollaborationSpaceConversationOutcome> =
            when (received) {
                is AppResult.Failed -> {
                    remember(received.failure)
                    failed(retainCollaborationSpaceConversationFailure(previous, received.failure))
                }
                is AppResult.Success -> {
                    val outcome = CollaborationSpaceConversationOutcome.Recorded(received.value)
                    if (
                        validCollaborationSpaceRoomCommand(
                            conversation,
                            sending.copy(revision = sending.revision + 1, outcome = outcome),
                        )
                    )
                        AppResult.Success(outcome)
                    else failed(invalid().failure)
                }
            }
        val outcome =
            when (result) {
                is AppResult.Failed ->
                    CollaborationSpaceConversationOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> result.value
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
